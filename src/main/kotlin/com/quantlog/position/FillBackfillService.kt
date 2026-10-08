package com.quantlog.position

import com.quantlog.broker.BrokerClient
import com.quantlog.broker.Market
import com.quantlog.sync.SyncMismatchReporter
import mu.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

private val log = KotlinLogging.logger {}
private val KST: ZoneId = ZoneId.of("Asia/Seoul")

/** [FillBackfillService.backfill] 한 번의 결과. */
data class BackfillResult(
    /** 원장에 보충 줄을 추가한 주문 수. */
    val filledOrders: Int = 0,
    /** 원장 합계가 KIS 보다 많아 이상한 주문 수(경고 로그를 남긴다). */
    val mismatchedOrders: Int = 0,
    /** KIS 를 실제로 불렀는가. 확인할 주문이 없으면 부르지 않는다. */
    val queriedKis: Boolean = false,
)

/**
 * 체결통보를 놓쳤을 때(서버 재시작·WebSocket 끊김) 빠진 체결을 KIS 체결내역 조회로 원장에 채우고, 원장과 KIS 가 어긋난 주문을 알린다
 * (docs/체결-원장-설계.md). 체결통보는 끊겨 있던 동안의 것을 다시 보내 주지 않는다.
 *
 * - KIS 가 주는 건 주문별 체결 **누적**이라, 보충 줄은 "KIS 누적 − 원장 합계" 만큼을 평균가 기준 1줄로 만든다(`REST_BACKFILL`).
 *   체결 시각은 채운 시각이고, 체결 직전 평단을 몰라 그 몫은 실현손익에 넣지 않는다(짐작으로 채우지 않는다).
 * - 방금 체결통보가 온 주문은 건너뛴다([QUIET]) — 통보가 오는 중에 보충하면 같은 체결이 두 번 들어갈 수 있다.
 * - 원장 합계가 KIS 보다 많으면(중복 반영 의심) 고치지 않고 불일치만 보고한다. 보충한 때도 보고한다([SyncMismatchReporter], CLAUDE.md "동기화 불일치 보고").
 * - 우리 매매 기록(Trade)에 있는 주문만 본다. 증권사 앱에서 직접 낸 주문은 대상이 아니다.
 */
@Service
class FillBackfillService(
    private val broker: BrokerClient,
    private val tradeRepository: TradeRepository,
    private val tradeFillRepository: TradeFillRepository,
) {
    @Transactional
    fun backfill(now: Instant = Instant.now()): BackfillResult {
        val today = now.atZone(KST).toLocalDate()
        val orders =
            tradeRepository.findAll().filter { it.market == Market.KR && it.executedAt.atZone(KST).toLocalDate() == today }
        if (orders.isEmpty()) return BackfillResult()

        val fillsByOrder = tradeFillRepository.findAll().filter { it.market == Market.KR }.groupBy { it.orderNo }
        // 이미 주문수량만큼 원장에 쌓인 주문은 볼 필요가 없다. 취소된 주문은 몇 주 체결됐는지 몰라 늘 확인한다.
        val candidates =
            orders.filter { trade ->
                trade.canceled || (fillsByOrder[trade.orderNo].orEmpty().sumOf { it.quantity }) < trade.quantity
            }
        if (candidates.isEmpty()) return BackfillResult()

        val fetched =
            runCatching { broker.todayOrderFills(Market.KR) }
                .onFailure { log.warn(it) { "[체결 보충] KIS 체결내역 조회 실패, 이번 회차 건너뜀: ${it.message}" } }
                .getOrNull() ?: return BackfillResult(queriedKis = true)
        val totals = fetched.associateBy { it.orderNo }

        var filled = 0
        var mismatched = 0
        candidates.forEach { trade ->
            val total = totals[trade.orderNo] ?: return@forEach
            val fills = fillsByOrder[trade.orderNo].orEmpty()
            val ledgerQuantity = fills.sumOf { it.quantity }
            when {
                total.filledQuantity > ledgerQuantity -> {
                    val lastActivity = (fills.maxOfOrNull { it.filledAt } ?: trade.executedAt)
                    if (Duration.between(lastActivity, now) < QUIET) return@forEach
                    val missing = total.filledQuantity - ledgerQuantity
                    val ledgerAmount = fills.sumOf { it.price.multiply(BigDecimal(it.quantity)) }
                    val price = missingPrice(total.averagePrice, total.filledQuantity, ledgerAmount, missing)
                    tradeFillRepository.save(
                        TradeFill(Market.KR, trade.symbol, trade.side, trade.orderNo, missing, price, null, now, FillSource.REST_BACKFILL),
                    )
                    filled++
                    SyncMismatchReporter.report(
                        area = "체결 보충",
                        subject = "주문 ${trade.orderNo} ${trade.symbol}",
                        db = "원장 ${ledgerQuantity}주",
                        external = "KIS ${total.filledQuantity}주",
                        action = "체결통보로 못 받은 ${missing}주를 원장에 채움 (평균 $price)",
                    )
                }
                total.filledQuantity < ledgerQuantity -> {
                    mismatched++
                    SyncMismatchReporter.report(
                        area = "체결 대조",
                        subject = "주문 ${trade.orderNo} ${trade.symbol}",
                        db = "원장 ${ledgerQuantity}주",
                        external = "KIS ${total.filledQuantity}주",
                        action = "중복 반영이 의심돼 고치지 않음 — 확인 필요",
                    )
                }
            }
        }
        return BackfillResult(filled, mismatched, queriedKis = true)
    }

    /** KIS 평균가(전체)에서 원장에 이미 있는 몫을 빼, 빠진 체결만의 평균가를 구한다. 계산이 이상하면(0 이하) 전체 평균가를 쓴다. */
    private fun missingPrice(
        averagePrice: BigDecimal,
        totalQuantity: Int,
        ledgerAmount: BigDecimal,
        missing: Int,
    ): BigDecimal {
        val missingAmount = averagePrice.multiply(BigDecimal(totalQuantity)).subtract(ledgerAmount)
        val price = missingAmount.divide(BigDecimal(missing), PRICE_SCALE, RoundingMode.HALF_UP)
        return if (price > BigDecimal.ZERO) price else averagePrice
    }

    private companion object {
        /** 이 주문에 마지막 체결이 쌓인 지 이만큼 안 지났으면 통보가 아직 오는 중일 수 있어 건너뛴다. */
        val QUIET: Duration = Duration.ofSeconds(30)
        const val PRICE_SCALE = 6
    }
}

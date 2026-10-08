package com.quantlog.position

import com.quantlog.broker.BrokerClient
import com.quantlog.broker.Market
import com.quantlog.sync.SyncMismatchReporter
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

private val log = KotlinLogging.logger {}
private val KST: ZoneId = ZoneId.of("Asia/Seoul")

/** [FillBackfillService.backfill] 한 번의 결과. */
data class BackfillResult(
    /** 게이트웨이 원장에 KIS 보다 체결이 적은 주문 수(게이트웨이가 통보를 놓쳤다). */
    val missingOrders: Int = 0,
    /** 원장 합계가 KIS 보다 많아 이상한 주문 수. */
    val mismatchedOrders: Int = 0,
    /** KIS 를 실제로 불렀는가. 확인할 주문이 없으면 부르지 않는다. */
    val queriedKis: Boolean = false,
)

/**
 * 게이트웨이 원장(`broker_notice`)이 체결통보를 놓쳤는지 KIS 체결내역 조회로 대조하고, 어긋난 주문을 `[동기화 불일치]` 로 알린다.
 * **원장에 줄을 채우지는 않는다** — 앱은 게이트웨이 원장에 쓰지 않고, 놓친 체결을 원장에 보충하는 일은 게이트웨이가 맡는다(docs/서버-분리.md
 * "정합성 안전장치 1", 아직 미구현). 그동안 보유 수량·평단은 잔고 스냅샷이 증권사 값으로 맞춘다.
 *
 * 앱이 원장을 늦게 반영하는 것(재시작·처리 지연)은 놓친 게 아니다 — 그래서 앱이 반영한 몫이 아니라 원장에 기록된 줄 전부와 비교한다.
 *
 * - KIS 가 주는 건 주문별 체결 **누적**이다. 원장 합계가 그보다 적으면 "게이트웨이가 놓침", 많으면 "중복 기록 의심"으로 보고한다.
 * - 방금 체결이 기록된 주문은 건너뛴다([QUIET]) — 통보가 아직 오는 중일 수 있다.
 * - 같은 주문·같은 수량의 어긋남은 한 번만 보고한다(3분마다 같은 오류가 쌓이지 않게).
 * - 우리 매매 기록(Trade)에 있는 오늘 주문만 본다. 원장이 돌기 시작한 시각(가장 이른 원장 체결) 전에 낸 주문과, 원장이 비어 있을 때는 보지 않는다.
 */
@Service
class FillBackfillService(
    private val broker: BrokerClient,
    private val tradeRepository: TradeRepository,
    private val orderFills: OrderFills,
) {
    /** 이미 보고한 어긋남(주문번호 → "원장 수량/KIS 수량"). */
    private val reported = boundedMap<String>()

    fun backfill(now: Instant = Instant.now()): BackfillResult {
        val today = now.atZone(KST).toLocalDate()
        val ledgerStart = orderFills.projectedAll().minOfOrNull { it.filledAt } ?: return BackfillResult()
        val orders =
            tradeRepository.findAll().filter {
                it.market == Market.KR && it.executedAt.atZone(KST).toLocalDate() == today && !it.executedAt.isBefore(ledgerStart)
            }
        if (orders.isEmpty()) return BackfillResult()

        val fillsByOrder = orders.associate { it.orderNo to orderFills.upTo(it.orderNo, Long.MAX_VALUE) }
        // 이미 주문수량만큼 원장에 쌓인 주문은 볼 필요가 없다. 취소된 주문은 몇 주 체결됐는지 몰라 늘 확인한다.
        val candidates =
            orders.filter { trade -> trade.canceled || fillsByOrder[trade.orderNo].orEmpty().total().quantity < trade.quantity }
        if (candidates.isEmpty()) return BackfillResult()

        val fetched =
            runCatching { broker.todayOrderFills(Market.KR) }
                .onFailure { log.warn(it) { "[체결 대조] KIS 체결내역 조회 실패, 이번 회차 건너뜀: ${it.message}" } }
                .getOrNull() ?: return BackfillResult(queriedKis = true)
        val totals = fetched.associateBy { it.orderNo }

        var missing = 0
        var mismatched = 0
        candidates.forEach { trade ->
            val total = totals[trade.orderNo] ?: return@forEach
            val fills = fillsByOrder[trade.orderNo].orEmpty()
            val ledgerQuantity = fills.total().quantity
            if (total.filledQuantity == ledgerQuantity) return@forEach
            val lastActivity = fills.maxOfOrNull { it.filledAt } ?: trade.executedAt
            if (Duration.between(lastActivity, now) < QUIET) return@forEach
            val missed = total.filledQuantity > ledgerQuantity
            if (missed) missing++ else mismatched++
            val signature = "$ledgerQuantity/${total.filledQuantity}"
            if (reported.put(trade.orderNo, signature) == signature) return@forEach
            SyncMismatchReporter.report(
                area = "체결 대조",
                subject = "주문 ${trade.orderNo} ${trade.symbol}",
                db = "게이트웨이 원장 ${ledgerQuantity}주",
                external = "KIS ${total.filledQuantity}주",
                action =
                    if (missed) {
                        "게이트웨이가 체결통보 ${total.filledQuantity - ledgerQuantity}주를 놓침 — 보유는 잔고 스냅샷이 맞추고, 이 체결의 손익은 빠진다"
                    } else {
                        "원장이 더 많다(중복 기록 의심) — 고치지 않음, 확인 필요"
                    },
            )
        }
        return BackfillResult(missing, mismatched, queriedKis = true)
    }

    private companion object {
        /** 이 주문에 마지막 체결이 기록된 지 이만큼 안 지났으면 통보가 아직 오는 중일 수 있어 건너뛴다. */
        val QUIET: Duration = Duration.ofSeconds(30)
    }
}

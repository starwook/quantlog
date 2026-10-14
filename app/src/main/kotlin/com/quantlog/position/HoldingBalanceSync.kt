package com.quantlog.position

import com.quantlog.broker.Holding
import com.quantlog.broker.Market
import com.quantlog.broker.Side
import com.quantlog.sync.SyncMismatchReporter
import com.quantlog.watchlist.SymbolStrategyService
import mu.KotlinLogging
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.MathContext
import java.time.Instant
import java.time.LocalDate

private val log = KotlinLogging.logger {}

/**
 * 게이트웨이의 잔고 스냅샷(증권사 값)을 보유 현황([AccountHolding])과 대조하고, 어긋나면 증권사 값으로 고친 뒤 `[동기화 불일치]` 로 보고한다.
 * 체결 통보로 그때그때 고치는 일은 [HoldingFillApplier] 가 한다.
 *
 * **같은 시점끼리만 비교한다.** 체결통보는 한투 체결보다 몇 초~20초 늦게 올 수 있다(2026-10-12 실측 최대 18.8초). 그 사이 온 잔고는 체결이 끝난 값이라
 * 그대로 덮어쓰면, 뒤늦게 오는 체결통보가 같은 체결을 한 번 더 얹는다(2026-10-12 226490: 평단 65,980 → 65,937.5 로 틀어져 매도 손익이 425원 어긋났다).
 * 잔고에는 종목별 당일 누적 매수·매도 수량이 있으니, **앱이 반영한 당일 누적(주문별 체결 합계 `order_fill`)과 같을 때만** "같은 시점"으로 보고 수량·평단을 비교한다.
 * 누적이 다르면 이유는 둘 중 하나다. ① 앱이 낸 주문의 체결통보가 아직 안 왔다 → 그 주문이 끝날 때까지 수량·평단은 건드리지 않는다(현재가만 갱신).
 * ② 기다릴 주문이 없는데 다르다(통보를 놓쳤다) → 시간을 재지 않고 바로 증권사 값으로 고친다.
 * 직접 거래는 하지 않는다는 전제라, 잔고에 누적이 없거나 종목이 사라졌을 때도 같은 규칙을 쓴다.
 */
@Service
class HoldingBalanceSync(
    private val accountHoldingRepository: AccountHoldingRepository,
    private val symbolNames: SymbolStrategyService,
    private val events: ApplicationEventPublisher,
    private val state: HoldingUpdateTimes,
    private val fillStates: OrderFillStates,
    private val tradeRepository: TradeRepository,
) {
    /**
     * [kis] 는 [fetchedAt] 시점에 받은 잔고 전체. 바뀐 내용 설명 목록을 돌려준다(변화가 없으면 빈 목록).
     * [fetchedAt] 은 KIS 를 부르기 **전** 시각이어야 한다 — 조회 도중 낸 주문을 "반영됨"으로 착각하지 않기 위해서다.
     */
    @Transactional
    fun sync(
        kis: List<Holding>,
        fetchedAt: Instant = Instant.now(),
    ): List<String> {
        val actual = kis.filter { it.quantity.toInt() > 0 }.associateBy { it.market to it.symbol }
        val existing = accountHoldingRepository.findAll().associateBy { it.market to it.symbol }
        val ledgerToday = ledgerTotalsOf(OrderFills.dayOf(fetchedAt))
        val changes = mutableListOf<String>()
        // 수량이 그대로여도 현재가가 바뀌면 화면(평가손익)이 갱신돼야 한다.
        var priceChanged = false

        (actual.keys + existing.keys).forEach { key ->
            // 이 조회를 시작한 뒤에 체결을 반영한 종목은 조회 결과가 체결 이전 값일 수 있어 이번 회차엔 건드리지 않는다(다음 회차가 맞춘다).
            if (state.fillAppliedAfter(key, fetchedAt)) return@forEach
            val holding = actual[key]
            val row = existing[key]
            val sameTime = holding != null && ledgerToday[key].orZero().matches(holding)
            val agrees = row != null && holding != null && matches(row, holding)

            if (agrees) {
                // 같은 시점으로 확인된 값이면 증권사 평단으로 맞춰 둔다(체결가로 계산한 평단과 한투 평단은 소수 자릿수만 다를 수 있다).
                if (row!!.currentPrice.compareTo(holding!!.currentPrice) != 0) priceChanged = true
                row.update(row.quantity, if (sameTime) holding.averagePrice else row.avgCost, holding.currentPrice)
                return@forEach
            }
            if (!sameTime && hasOrderInFlight(key, fetchedAt)) {
                // 체결통보가 덜 왔을 수 있다 — 수량·평단은 두고 현재가만 갱신한다.
                if (row != null && holding != null && row.currentPrice.compareTo(holding.currentPrice) != 0) {
                    row.update(row.quantity, row.avgCost, holding.currentPrice)
                    priceChanged = true
                }
                return@forEach
            }
            when {
                row == null -> changes += addMissing(holding!!)
                holding == null -> changes += removeGone(row)
                else -> {
                    changes += listOfNotNull(correct(row, holding))
                    if (row.currentPrice.compareTo(holding.currentPrice) != 0) priceChanged = true
                    row.update(holding.quantity.toInt(), holding.averagePrice, holding.currentPrice)
                }
            }
        }

        state.lastSyncedAt = fetchedAt
        changes.forEach { log.info { "[잔고 동기화] $it" } }
        if (changes.isNotEmpty() || priceChanged) events.publishEvent(HoldingsChangedEvent)
        return changes
    }

    private fun matches(
        row: AccountHolding,
        holding: Holding,
    ) = row.quantity == holding.quantity.toInt() && !averageDiffers(row.avgCost, holding.averagePrice)

    /** 앱이 보유 현황에 반영한 [day] 의 종목별 누적 매수·매도 수량(주문별 체결 합계 `order_fill` — 체결통보와, 통보가 빠졌으면 체결조회로 채운 몫). */
    private fun ledgerTotalsOf(day: LocalDate): Map<Pair<Market, String>, Totals> =
        fillStates.ofDay(day)
            .groupBy { it.market to it.symbol }
            .mapValues { (_, fills) ->
                Totals(
                    bought = fills.filter { it.side == Side.BUY }.sumOf { it.filled.quantity },
                    sold = fills.filter { it.side == Side.SELL }.sumOf { it.filled.quantity },
                )
            }

    private data class Totals(val bought: Int, val sold: Int) {
        /** 잔고의 당일 누적과 같은가. 잔고에 누적이 없으면 같은 시점인지 알 수 없으니 false. */
        fun matches(holding: Holding): Boolean {
            val b = holding.boughtToday ?: return false
            val s = holding.soldToday ?: return false
            return b.compareTo(BigDecimal(bought)) == 0 && s.compareTo(BigDecimal(sold)) == 0
        }
    }

    private fun Totals?.orZero() = this ?: Totals(0, 0)

    /**
     * 이 종목에 앱이 낸 주문 중 체결통보가 아직 다 안 온 주문이 있는가(취소된 주문은 뺀다). 있으면 잔고와의 어긋남은 그 통보가 늦는 탓일 수 있다.
     * 주문이 영영 안 끝나 보유 대조가 막히지 않도록 [ORDER_IN_FLIGHT_LIMIT] 이전 주문은 보지 않는다.
     */
    private fun hasOrderInFlight(
        key: Pair<Market, String>,
        fetchedAt: Instant,
    ): Boolean =
        tradeRepository.findAllByMarketAndSymbolAndExecutedAtAfter(key.first, key.second, fetchedAt.minus(ORDER_IN_FLIGHT_LIMIT))
            .any { !it.canceled && fillStates.filled(it.orderNo, OrderFills.dayOf(it.executedAt), it.market).quantity < it.quantity }

    private fun label(
        market: Market,
        symbol: String,
    ) = "${symbolNames.displayName(market, symbol)}($symbol)"

    private fun addMissing(holding: Holding): String {
        val quantity = holding.quantity.toInt()
        accountHoldingRepository.save(AccountHolding(holding.market, holding.symbol, quantity, holding.averagePrice, holding.currentPrice))
        SyncMismatchReporter.report(
            AREA,
            label(holding.market, holding.symbol),
            "보유 없음",
            "${quantity}주 (평단 ${holding.averagePrice.plain()})",
            "DB 에 추가",
        )
        return "${symbolNames.displayName(holding.market, holding.symbol)} 신규 ${quantity}주 (평단 ${holding.averagePrice})"
    }

    /** 수량이 다르면 바뀐 내용을 돌려주고, 평단만 다르면 보고만 한다. 값을 덮어쓰는 건 호출한 쪽이다. */
    private fun correct(
        row: AccountHolding,
        holding: Holding,
    ): String? {
        val quantity = holding.quantity.toInt()
        val subject = label(row.market, row.symbol)
        if (row.quantity != quantity) {
            SyncMismatchReporter.report(
                AREA,
                subject,
                "${row.quantity}주 (평단 ${row.avgCost.plain()})",
                "${quantity}주 (평단 ${holding.averagePrice.plain()})",
                "DB 를 증권사 값으로 고침",
            )
            return "${symbolNames.displayName(row.market, row.symbol)} ${row.quantity}주 → ${quantity}주 (평단 ${holding.averagePrice})"
        }
        if (averageDiffers(row.avgCost, holding.averagePrice)) {
            SyncMismatchReporter.report(
                AREA,
                subject,
                "평단 ${row.avgCost.plain()} (${quantity}주)",
                "평단 ${holding.averagePrice.plain()} (${quantity}주)",
                "DB 평단을 증권사 값으로 고침",
            )
        }
        return null
    }

    private fun removeGone(row: AccountHolding): String {
        accountHoldingRepository.delete(row)
        SyncMismatchReporter.report(AREA, label(row.market, row.symbol), "${row.quantity}주", "보유 없음", "DB 에서 삭제")
        return "${symbolNames.displayName(row.market, row.symbol)} 잔고에서 사라짐 (${row.quantity}주)"
    }

    /**
     * 평단이 "어긋났다"고 볼 기준: 두 값의 차이가 큰 쪽의 0.01% 를 넘을 때. 체결가들로 직접 계산한 평단과 증권사가 주는 평단은
     * 소수 자릿수·반올림이 달라 미세하게 다를 수 있어서, 그 정도로는 매번 오류를 내지 않는다.
     */
    private fun averageDiffers(
        db: BigDecimal,
        kis: BigDecimal,
    ): Boolean {
        val base = db.abs().max(kis.abs())
        if (base.signum() == 0) return false
        return db.subtract(kis).abs().divide(base, MathContext.DECIMAL64) > AVERAGE_TOLERANCE
    }

    private companion object {
        val AVERAGE_TOLERANCE: BigDecimal = BigDecimal("0.0001")

        const val AREA = "잔고 동기화"
    }
}

package com.quantlog.position

import com.quantlog.broker.FillNotice
import com.quantlog.broker.Market
import com.quantlog.broker.OrderFillTotal
import com.quantlog.broker.Side
import com.quantlog.sync.SyncMismatchReporter
import com.quantlog.watchlist.SymbolStrategyService
import mu.KotlinLogging
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate

private val log = KotlinLogging.logger {}

/**
 * 주문의 체결 상태([OrderFillState], `order_fill`)를 갱신하고, 그 주문에서 **새로 늘어난 체결만큼** 보유 현황([AccountHolding])의 수량·평단을 반영한다.
 * 체결을 알게 되는 길은 둘이다 — 원장(`kis_broker_fill`)의 체결통보([applyFill], [com.quantlog.gatewayclient.FillProjector] 가 원장 순서대로)와
 * 한투 체결조회([applyInquiry], [FillReconcileService]). 주문의 체결은 두 출처 중 수량이 큰 쪽이라([OrderFillState.filled]) 어느 쪽이 먼저 와도 두 번 반영하지 않는다.
 * 증권사 잔고로 덮어쓰는 일은 [HoldingBalanceSync] 가 한다. 보유 현황을 고치는 일은 모두 한 스레드에서 차례로 한다([com.quantlog.trading.HoldingSyncScheduler]).
 */
@Service
class HoldingFillApplier(
    private val accountHoldingRepository: AccountHoldingRepository,
    private val tradeRepository: TradeRepository,
    private val fillStates: OrderFillStateRepository,
    private val orderFills: OrderFills,
    private val symbolNames: SymbolStrategyService,
    private val events: ApplicationEventPublisher,
    private val state: HoldingUpdateTimes,
) {
    /**
     * 원장(`kis_broker_fill`)의 체결 통보 한 줄([noticeId])을 반영한다. 체결 통보만 처리하고 접수 통보는 건너뛴다.
     * 매수는 수량 가중평균으로 평단을 다시 계산하고, 매도는 수량만 줄인다(0이 되면 행 삭제). 바뀐 내용 설명을 돌려준다(건너뛰면 null).
     * 부분체결 통보의 체결수량은 건별이다(2026-10-07 실측: 100주 매도가 44주 + 56주로 왔다). 그대로 더하되 주문수량을 넘기지 않게만 막는다.
     * 체결조회가 이 체결을 먼저 알려 이미 반영했으면 통보 합계만 늘고 보유는 그대로다.
     * [receivedAt] 은 게이트웨이가 통보를 받은 시각이다 — 앱이 늦게 반영해도 체결 시각은 이것이다.
     */
    @Transactional
    fun applyFill(
        notice: FillNotice,
        noticeId: Long,
        receivedAt: Instant,
        now: Instant = Instant.now(),
    ): String? {
        if (!notice.isFill) return null
        val side = notice.side ?: return null
        val price = notice.filledPrice ?: return null
        val reported = notice.filledQuantity?.toInt() ?: return null
        val day = OrderFills.dayOf(receivedAt)
        val orderQuantity = orderQuantityOf(notice, day)
        val fill = stateOf(day, notice.orderNo, notice.symbol, side)
        val quantity = minOf(reported, orderQuantity - fill.noticeQuantity).coerceAtLeast(0)
        if (quantity <= 0) return null

        val before = fill.filled
        fill.addNotice(quantity, price * BigDecimal(quantity), now)
        fill.settleIfFull(orderQuantity)
        fillStates.save(fill)
        val change = applyIncrease(fill, before, receivedAt, noticeId, now) ?: return null
        log.info { "[체결통보 반영] ${notice.orderNo} $change" }
        return change
    }

    /**
     * 한투 체결조회가 알려 준 이 주문의 누적([total])을 반영한다. 체결통보 합계보다 많으면 빠진 몫(통보 유실)을 보유·평단에 더하고 `[동기화 불일치]` 로 보고한다.
     * 남은 수량이 0 이면 주문이 끝난 것으로 적는다([OrderFillState.settled]). 끝났는데 통보 합계가 조회보다 많으면 고치지 않고 보고만 한다.
     * 바뀐 내용 설명을 돌려준다(늘어난 체결이 없으면 null).
     */
    @Transactional
    fun applyInquiry(
        trade: Trade,
        total: OrderFillTotal,
        now: Instant = Instant.now(),
    ): String? {
        val day = OrderFills.dayOf(trade.executedAt)
        val fill = stateOf(day, trade.orderNo, trade.symbol, trade.side)
        val before = fill.filled
        val noticed = fill.noticeQuantity
        val amount = total.filledAmount ?: total.averagePrice.multiply(BigDecimal(total.filledQuantity))
        fill.recordInquiry(minOf(total.filledQuantity, trade.quantity), amount, total.remainingQuantity == 0, now)
        fill.settleIfFull(trade.quantity)
        fillStates.save(fill)
        val subject = "주문 ${trade.orderNo} ${symbolNames.displayName(trade.market, trade.symbol)}(${trade.symbol})"
        if (fill.settled && noticed > fill.inquiryQuantity) {
            SyncMismatchReporter.report(
                AREA_INQUIRY,
                subject,
                "체결통보 ${noticed}주",
                "체결조회 ${fill.inquiryQuantity}주",
                "통보가 더 많다 — 고치지 않음, 확인 필요",
            )
        }
        val change = applyIncrease(fill, before, now, null, now) ?: return null
        SyncMismatchReporter.report(
            AREA_INQUIRY,
            subject,
            "체결통보 ${noticed}주",
            "체결조회 ${fill.inquiryQuantity}주 (평균 ${total.averagePrice.plain()})",
            "통보가 빠진 ${fill.filled.quantity - before.quantity}주를 보유·평단에 반영",
        )
        log.info { "[체결조회 반영] ${trade.orderNo} $change" }
        return change
    }

    /**
     * 오늘 `order_fill` 이 비어 있으면(이 기능을 장중에 처음 배포한 날) 이미 반영을 끝낸 오늘 원장 줄로 주문별 체결통보 합계를 채운다. 보유 현황은 이미 그 줄들을
     * 반영했으므로 건드리지 않는다. 채운 주문 수를 돌려준다.
     */
    @Transactional
    fun rebuildToday(now: Instant = Instant.now()): Int {
        val today = OrderFills.today()
        if (fillStates.existsByTradeDate(today)) return 0
        val byOrder = orderFills.projectedAll().filter { OrderFills.dayOf(it.filledAt) == today }.groupBy { it.orderNo }
        byOrder.forEach { (orderNo, fills) ->
            val first = fills.first()
            val fill = OrderFillState(today, first.market, orderNo, first.symbol, first.side)
            val total = fills.total()
            fill.addNotice(total.quantity, total.amount, now)
            tradeRepository.findByOrderNo(first.market, orderNo, today)?.let { fill.settleIfFull(it.quantity) }
            fillStates.save(fill)
        }
        if (byOrder.isNotEmpty()) log.info { "[체결 반영] 오늘 원장으로 주문별 체결 합계를 다시 채웠다: ${byOrder.size}건" }
        return byOrder.size
    }

    private fun stateOf(
        day: LocalDate,
        orderNo: String,
        symbol: String,
        side: Side,
    ): OrderFillState =
        fillStates.findByTradeDateAndMarketAndOrderNo(day, Market.KR, orderNo) ?: OrderFillState(day, Market.KR, orderNo, symbol, side)

    /**
     * 주문의 체결이 [before] 에서 지금([OrderFillState.filled])으로 늘어난 몫을 보유 현황에 반영한다. 늘어난 금액 ÷ 늘어난 수량을 그 몫의 체결가로 쓴다
     * (체결조회로 채운 몫은 건별 체결가를 모르므로 이렇게 거꾸로 계산한다). 늘어난 게 없으면 null.
     */
    private fun applyIncrease(
        fill: OrderFillState,
        before: FilledSoFar,
        filledAt: Instant,
        noticeId: Long?,
        now: Instant,
    ): String? {
        val after = fill.filled
        val quantity = after.quantity - before.quantity
        if (quantity <= 0) return null
        val price = (after.amount - before.amount).divide(BigDecimal(quantity), COST_SCALE, RoundingMode.HALF_UP)

        val market = fill.market
        val name = symbolNames.displayName(market, fill.symbol)
        val row = accountHoldingRepository.findByMarketAndSymbol(market, fill.symbol)
        // 매매 기록(TradeService)·화면에 알린다. 사본을 바꾸기 전의 평단을 실어 보내야 한다 — 매도 손익은 그 평단 기준으로 확정한다.
        // 사본에 종목이 없는 매도(row == null)도 체결은 실제로 일어났으니 알린다(평단은 null).
        events.publishEvent(
            FillAppliedEvent(market, fill.symbol, fill.side, fill.orderNo, quantity, price, row?.avgCost, filledAt, noticeId),
        )
        // 매도 원가는 이 몫이 빠지는 지금의 평단으로 쌓는다(부분 매도 사이에 매수가 끼어 평단이 바뀌어도 손익이 맞게).
        if (fill.side == Side.SELL && row != null) fill.addCostBasis(minOf(quantity, row.quantity), row.avgCost)
        val change =
            when (fill.side) {
                Side.BUY -> applyBuy(row, fill, name, quantity, price)
                Side.SELL -> applySell(row, fill, name, quantity, price) ?: return null
            }
        fillStates.save(fill)
        state.markFillApplied(market to fill.symbol, now)
        events.publishEvent(HoldingsChangedEvent)
        return change
    }

    private fun applyBuy(
        row: AccountHolding?,
        fill: OrderFillState,
        name: String,
        quantity: Int,
        price: BigDecimal,
    ): String {
        if (row == null) {
            accountHoldingRepository.save(AccountHolding(Market.KR, fill.symbol, quantity, price, price))
            return "$name 신규 ${quantity}주 (체결 $price)"
        }
        val total = row.quantity + quantity
        val cost = row.avgCost.multiply(BigDecimal(row.quantity)).add(price.multiply(BigDecimal(quantity)))
        row.update(total, cost.divide(BigDecimal(total), COST_SCALE, RoundingMode.HALF_UP), price)
        return "$name ${row.quantity - quantity}주 → ${total}주 (체결 $price, 평단 ${row.avgCost.stripTrailingZeros().toPlainString()})"
    }

    /** 반영했으면 바뀐 내용, 보유 사본에 종목이 없어 건너뛰었으면 null(다음 잔고 동기화가 맞춘다). */
    private fun applySell(
        row: AccountHolding?,
        fill: OrderFillState,
        name: String,
        quantity: Int,
        price: BigDecimal,
    ): String? {
        val subject = "$name(${fill.symbol}) 주문번호=${fill.orderNo}"
        if (row == null) {
            SyncMismatchReporter.report(
                AREA_FILL,
                subject,
                "보유 없음",
                "${quantity}주 매도 체결 (${price.plain()})",
                "반영 건너뜀 — 다음 KIS 잔고 동기화가 맞춘다",
            )
            return null
        }
        val left = row.quantity - quantity
        if (left < 0) {
            SyncMismatchReporter.report(AREA_FILL, subject, "${row.quantity}주 보유", "${quantity}주 매도 체결 (${price.plain()})", "보유를 0주로 고침")
        }
        if (left <= 0) {
            accountHoldingRepository.delete(row)
            return "$name 전량 매도 (${row.quantity}주 → 0주, 체결 $price)"
        }
        row.update(left, row.avgCost, price)
        return "$name ${row.quantity + quantity}주 → ${left}주 (체결 $price)"
    }

    /**
     * 주문수량. 체결통보가 주문수량을 0 으로 보내오는 경우가 있다(2026-10-07 실측: 쪼개져 온 매도 체결 두 건 모두 0 → 반영이 통째로 건너뛰어졌다).
     * 0 이하면 우리 매매 기록(Trade)의 수량을 쓰고, 그것도 없으면 상한 없이 체결수량을 그대로 믿는다.
     */
    private fun orderQuantityOf(
        notice: FillNotice,
        day: LocalDate,
    ): Int {
        val reported = notice.orderQuantity?.toInt() ?: 0
        if (reported > 0) return reported
        return tradeRepository.findByOrderNo(Market.KR, notice.orderNo, day)?.quantity ?: Int.MAX_VALUE
    }

    private companion object {
        /** account_holding.avg_cost 의 소수 자릿수. */
        const val COST_SCALE = 6
        const val AREA_FILL = "체결통보 반영"
        const val AREA_INQUIRY = "체결 대조"
    }
}

internal fun BigDecimal.plain(): String = stripTrailingZeros().toPlainString()

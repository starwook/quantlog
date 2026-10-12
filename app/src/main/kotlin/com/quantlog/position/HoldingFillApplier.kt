package com.quantlog.position

import com.quantlog.broker.FillNotice
import com.quantlog.broker.Market
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
 * 게이트웨이 원장(`kis_broker_fill`)의 체결 줄을 [com.quantlog.gatewayclient.FillProjector] 가 순서대로 넘겨 주면
 * 그만큼 보유 현황([AccountHolding])의 수량·평단을 계산해 반영한다. 증권사 잔고로 덮어쓰는 일은 [HoldingBalanceSync] 가 한다.
 */
@Service
class HoldingFillApplier(
    private val accountHoldingRepository: AccountHoldingRepository,
    private val tradeRepository: TradeRepository,
    private val orderFills: OrderFills,
    private val symbolNames: SymbolStrategyService,
    private val events: ApplicationEventPublisher,
    private val state: HoldingUpdateTimes,
) {
    /**
     * 원장(`kis_broker_fill`)의 체결 통보 한 줄([noticeId])을 보유 현황에 반영한다. 체결 통보만 처리하고 접수 통보는 건너뛴다.
     * 매수는 수량 가중평균으로 평단을 다시 계산하고, 매도는 수량만 줄인다(0이 되면 행 삭제). 바뀐 내용 설명을 돌려준다(건너뛰면 null).
     * 부분체결 통보의 체결수량은 건별이다(2026-10-07 실측: 100주 매도가 44주 + 56주로 왔다). 그대로 더하고 빼되, 같은 통보가 중복 와도 주문수량을 넘기지 않게만 막는다.
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
        val quantity = reserveFillQuantity(notice.orderNo, noticeId, reported, orderQuantityOf(notice, day), day)
        if (quantity <= 0) return null

        val market = Market.KR
        val name = symbolNames.displayName(market, notice.symbol)
        val row = accountHoldingRepository.findByMarketAndSymbol(market, notice.symbol)
        // 매매 기록(TradeService)·화면에 알린다. 사본을 바꾸기 전의 평단을 실어 보내야 한다 — 매도 손익은 그 평단 기준으로 확정한다.
        // 사본에 종목이 없는 매도(row == null)도 체결은 실제로 일어났으니 알린다(평단은 null).
        events.publishEvent(
            FillAppliedEvent(market, notice.symbol, side, notice.orderNo, quantity, price, row?.avgCost, receivedAt, noticeId),
        )
        val change =
            when (side) {
                Side.BUY -> applyBuy(row, notice, name, quantity, price)
                Side.SELL -> applySell(row, notice, name, quantity, price) ?: return null
            }
        state.markFillApplied(market to notice.symbol, now)
        log.info { "[체결통보 반영] ${notice.orderNo} $change" }
        events.publishEvent(HoldingsChangedEvent)
        return change
    }

    private fun applyBuy(
        row: AccountHolding?,
        notice: FillNotice,
        name: String,
        quantity: Int,
        price: BigDecimal,
    ): String {
        if (row == null) {
            accountHoldingRepository.save(AccountHolding(Market.KR, notice.symbol, quantity, price, price))
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
        notice: FillNotice,
        name: String,
        quantity: Int,
        price: BigDecimal,
    ): String? {
        val subject = "$name(${notice.symbol}) 주문번호=${notice.orderNo}"
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

    /**
     * 이 주문에서 이번 줄로 더 반영할 수량. 원장에서 이 줄 앞의 체결 합계가 이미 주문수량만큼이면 0 — 같은 통보가 중복 기록돼도,
     * 체결수량이 누적으로 와도 주문수량을 넘겨 더하지 않는다.
     */
    private fun reserveFillQuantity(
        orderNo: String,
        noticeId: Long,
        reported: Int,
        orderQuantity: Int,
        day: LocalDate,
    ): Int = minOf(reported, orderQuantity - orderFills.upTo(orderNo, noticeId - 1, day).total().quantity).coerceAtLeast(0)

    private companion object {
        /** account_holding.avg_cost 의 소수 자릿수. */
        const val COST_SCALE = 6
        const val AREA_FILL = "체결통보 반영"
    }
}

internal fun BigDecimal.plain(): String = stripTrailingZeros().toPlainString()

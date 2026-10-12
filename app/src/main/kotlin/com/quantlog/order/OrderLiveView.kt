package com.quantlog.order

import com.quantlog.common.KST
import com.quantlog.common.SHORT_DATE_TIME
import com.quantlog.common.money
import com.quantlog.common.pnlCss
import com.quantlog.common.signedMoneyAndPercent
import com.quantlog.position.OrderState
import com.quantlog.position.RealizedPnl
import com.quantlog.position.Trade

/** 실시간 주문 화면이 그대로 찍을 수 있게 포맷을 끝낸 주문 한 건. 화면에는 포맷·색상 판정 로직을 두지 않는다. */
data class OrderLiveView(
    val key: String,
    val market: String,
    val symbol: String,
    val symbolName: String,
    val side: String,
    val quantity: Int,
    val orderPriceText: String,
    /** 체결가. 체결 전이면 null. */
    val filledPriceText: String?,
    val state: OrderState,
    /** 지금까지 체결된 수량. [OrderState.PARTIAL] 일 때만 채운다(n/[quantity]주 표시용). */
    val filledQuantity: Int?,
    val reason: String,
    val placedAtEpochMs: Long,
    /** 체결된 매도 행에만 채운다. 계산이 안 되면 null. */
    val pnlText: String?,
    /** 손익의 근거(매수 평단 → 체결가 · 수량). pnlText 가 있을 때만 채운다. */
    val pnlDetailText: String?,
    val pnlCss: String,
    val timeText: String,
) {
    companion object {
        fun of(
            trade: Trade,
            symbolName: String,
            pnl: RealizedPnl?,
        ): OrderLiveView {
            val currency = trade.market.currency
            return OrderLiveView(
                key = "${trade.market}:${trade.orderNo}",
                market = trade.market.name,
                symbol = trade.symbol,
                symbolName = symbolName,
                side = trade.side.name,
                quantity = trade.quantity,
                orderPriceText = trade.orderPrice.money(currency),
                filledPriceText = trade.filledPrice?.money(currency),
                state = trade.state,
                filledQuantity = trade.filledQuantity.takeIf { trade.partiallyFilled },
                reason = trade.reason ?: "",
                placedAtEpochMs = trade.executedAt.toEpochMilli(),
                pnlText = pnl?.text(currency),
                pnlDetailText =
                    pnl?.let {
                        "매수 평단 ${it.avgBuyPrice.money(
                            currency,
                        )} → 체결 ${trade.filledPrice?.money(currency)} · ${it.matchedQuantity}주"
                    },
                pnlCss = pnl?.css() ?: "muted",
                timeText = SHORT_DATE_TIME.format(trade.executedAt.atZone(KST)),
            )
        }

        private fun RealizedPnl.text(currency: String) = signedMoneyAndPercent(amount, percent, currency)

        private fun RealizedPnl.css() = pnlCss(amount)
    }
}

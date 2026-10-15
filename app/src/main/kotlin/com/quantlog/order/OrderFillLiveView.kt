package com.quantlog.order

import com.quantlog.common.KST
import com.quantlog.common.SHORT_DATE_TIME
import com.quantlog.common.money
import com.quantlog.common.pnlCss
import com.quantlog.common.signedMoneyAndPercent
import com.quantlog.position.OrderFill
import com.quantlog.position.OrderFillState
import com.quantlog.position.RealizedPnl
import com.quantlog.position.Trade
import com.quantlog.position.total
import java.time.Instant

/** 주문 한 줄을 펼쳤을 때 보이는 체결 한 건. 체결통보(원장 한 줄)이거나, 통보가 빠져 체결조회로 채운 몫이다. */
data class FillDetailView(val text: String, val timeText: String)

/**
 * 체결 내역 한 줄 = **주문 하나**(`order_fill`, 2026-10-14). 체결이 늘 때마다 같은 [key] 의 줄이 새 값으로 바뀐다.
 * 수량·평균가는 체결통보와 체결조회 중 큰 쪽이고, 매도 손익은 체결 몫마다 그때 평단으로 쌓은 원가 기준이다([RealizedPnl]).
 * [details] 는 펼쳐 보는 건별 체결(원장 줄)과, 원장보다 체결이 많으면 그 차이(체결조회로 채움) 한 줄이다. [orderNo] 로 화면이 사유를 찾아 붙인다.
 */
data class OrderFillLiveView(
    val key: String,
    val orderNo: String,
    val market: String,
    val symbol: String,
    val symbolName: String,
    val side: String,
    val quantity: Int,
    val priceText: String,
    /** 체결 / 일부 체결 n/총 / 일부 체결 후 취소 n/총. */
    val stateText: String,
    val stateCss: String,
    /** 마지막 체결 시각(정렬용) — 원장 줄이 없으면 갱신 시각. */
    val placedAtEpochMs: Long,
    val timeText: String,
    val pnlText: String?,
    val pnlDetailText: String?,
    val pnlCss: String,
    val details: List<FillDetailView>,
) {
    companion object {
        fun of(
            fill: OrderFillState,
            ledger: List<OrderFill>,
            trade: Trade?,
            pnl: RealizedPnl?,
            symbolName: String,
        ): OrderFillLiveView {
            val currency = fill.market.currency
            val filled = fill.filled
            val orderQuantity = trade?.quantity
            val lastAt = ledger.maxOfOrNull { it.filledAt } ?: fill.updatedAt
            val details =
                ledger.sortedBy { it.id }.map { FillDetailView("${it.quantity}주 @ ${it.price.money(currency)}", time(it.filledAt)) } +
                    gapDetail(fill, ledger, currency)
            return OrderFillLiveView(
                key = "of:${fill.tradeDate}:${fill.orderNo}",
                orderNo = fill.orderNo,
                market = fill.market.name,
                symbol = fill.symbol,
                symbolName = symbolName,
                side = fill.side.name,
                quantity = filled.quantity,
                priceText = (filled.averagePrice ?: java.math.BigDecimal.ZERO).money(currency),
                stateText =
                    when {
                        orderQuantity == null || filled.quantity >= orderQuantity -> "체결"
                        trade.canceled || fill.settled -> "일부 체결 후 취소 ${filled.quantity}/$orderQuantity"
                        else -> "일부 체결 ${filled.quantity}/$orderQuantity"
                    },
                stateCss = if (orderQuantity == null || filled.quantity >= orderQuantity) "FILLED" else "PARTIAL",
                placedAtEpochMs = lastAt.toEpochMilli(),
                timeText = time(lastAt),
                pnlText = pnl?.let { signedMoneyAndPercent(it.amount, it.percent, currency) },
                pnlDetailText =
                    pnl?.let {
                        "매수 원가 평균 ${it.avgBuyPrice.money(
                            currency,
                        )} → 매도 평균 ${(filled.averagePrice ?: it.avgBuyPrice).money(currency)} · ${it.matchedQuantity}주"
                    },
                pnlCss = pnl?.let { pnlCss(it.amount) } ?: "muted",
                details = details,
            )
        }

        /** 원장에 없는 체결(통보가 빠져 체결조회로 채운 몫). 체결조회는 체결 시각을 주지 않는다. */
        private fun gapDetail(
            fill: OrderFillState,
            ledger: List<OrderFill>,
            currency: String,
        ): List<FillDetailView> {
            val noticed = ledger.total()
            val gap = fill.filled.quantity - noticed.quantity
            if (gap <= 0) return emptyList()
            val price = (fill.filled.amount - noticed.amount).divide(java.math.BigDecimal(gap), 0, java.math.RoundingMode.HALF_UP)
            return listOf(FillDetailView("${gap}주 @ 평균 ${price.money(currency)} — 체결통보가 빠져 체결조회로 채움", "시각 모름"))
        }

        private fun time(at: Instant) = SHORT_DATE_TIME.format(at.atZone(KST))
    }
}

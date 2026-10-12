package com.quantlog.order

import com.quantlog.broker.Market
import com.quantlog.broker.Side
import com.quantlog.common.KST
import com.quantlog.common.SHORT_DATE_TIME
import com.quantlog.common.money
import com.quantlog.common.pnlCss
import com.quantlog.common.signedMoneyAndPercent
import com.quantlog.position.FillAppliedEvent
import com.quantlog.position.OrderFill
import java.math.BigDecimal
import java.math.MathContext
import java.time.Instant

/**
 * 체결 내역 한 줄 — 체결통보 1건(게이트웨이 원장 `kis_broker_fill` 한 줄)이다. 주문 단위가 아니라서 부분체결이 쪼개진 그대로 보인다.
 * [orderNo] 로 화면이 같은 주문의 사유(reason)를 찾아 붙인다.
 */
data class FillLiveView(
    val key: String,
    val orderNo: String,
    val market: String,
    val symbol: String,
    val symbolName: String,
    val side: String,
    val quantity: Int,
    val priceText: String,
    val placedAtEpochMs: Long,
    val timeText: String,
    /** 매도이고 체결 직전 평단을 알 때만 채운다. */
    val pnlText: String?,
    val pnlDetailText: String?,
    val pnlCss: String,
) {
    companion object {
        /** 원장 한 줄. [avgCostBefore] 는 그 주문의 체결 직전 평단(매도만, 모르면 null). */
        fun of(
            fill: OrderFill,
            avgCostBefore: BigDecimal?,
            symbolName: String,
        ): FillLiveView =
            build(
                Facts(
                    market = fill.market,
                    symbol = fill.symbol,
                    side = fill.side,
                    orderNo = fill.orderNo,
                    quantity = fill.quantity,
                    price = fill.price,
                    avgCostBefore = avgCostBefore,
                    filledAt = fill.filledAt,
                ),
                key = ledgerKey(fill.id),
                symbolName = symbolName,
            )

        /** 방금 반영된 원장 한 줄. 키는 DB 에서 읽은 같은 줄([of])과 같다. */
        fun of(
            event: FillAppliedEvent,
            symbolName: String,
        ): FillLiveView =
            build(
                Facts(
                    market = event.market,
                    symbol = event.symbol,
                    side = event.side,
                    orderNo = event.orderNo,
                    quantity = event.quantity,
                    price = event.price,
                    avgCostBefore = event.avgCostBefore,
                    filledAt = event.filledAt,
                ),
                key = ledgerKey(event.noticeId),
                symbolName = symbolName,
            )

        private fun ledgerKey(noticeId: Long) = "bf:$noticeId"

        private class Facts(
            val market: Market,
            val symbol: String,
            val side: Side,
            val orderNo: String,
            val quantity: Int,
            val price: BigDecimal,
            val avgCostBefore: BigDecimal?,
            val filledAt: Instant,
        )

        private fun build(
            f: Facts,
            key: String,
            symbolName: String,
        ): FillLiveView {
            val currency = f.market.currency
            val avg = f.avgCostBefore?.takeIf { f.side == Side.SELL && it.signum() > 0 }
            val amount = avg?.let { f.price.subtract(it).multiply(BigDecimal(f.quantity)) }
            val percent = avg?.let { f.price.subtract(it).multiply(BigDecimal(100)).divide(it, MathContext.DECIMAL64) }
            return FillLiveView(
                key = key,
                orderNo = f.orderNo,
                market = f.market.name,
                symbol = f.symbol,
                symbolName = symbolName,
                side = f.side.name,
                quantity = f.quantity,
                priceText = f.price.money(currency),
                placedAtEpochMs = f.filledAt.toEpochMilli(),
                timeText = SHORT_DATE_TIME.format(f.filledAt.atZone(KST)),
                pnlText = if (amount != null && percent != null) signedMoneyAndPercent(amount, percent, currency) else null,
                pnlDetailText = avg?.let { "매수 평단 ${it.money(currency)} → 체결 ${f.price.money(currency)} · ${f.quantity}주" },
                pnlCss = amount?.let { pnlCss(it) } ?: "muted",
            )
        }
    }
}

package com.quantlog.order

import com.quantlog.position.AccountHolding
import com.quantlog.position.OrderState
import com.quantlog.position.RealizedPnl
import com.quantlog.position.Trade
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val KST: ZoneId = ZoneId.of("Asia/Seoul")
private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
private val DATE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")

/** 원화는 소수점 없이, 그 외는 소수점 둘째 자리까지. */
private fun BigDecimal.money(currency: String): String = String.format(Locale.US, "%,.${if (currency == "KRW") 0 else 2}f", this)

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
    val reason: String,
    val placedAtEpochMs: Long,
    /** 체결된 매도 행에만 채운다. 계산이 안 되면 null. */
    val pnlText: String?,
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
                reason = trade.reason ?: "",
                placedAtEpochMs = trade.executedAt.toEpochMilli(),
                pnlText = pnl?.text(currency),
                pnlCss = pnl?.css() ?: "muted",
                timeText = DATE_TIME_FORMAT.format(trade.executedAt.atZone(KST)),
            )
        }

        private fun RealizedPnl.text(currency: String): String {
            val sign = if (amount.signum() > 0) "+" else ""
            return "실현손익 $sign${amount.money(currency)} (${percent.setScale(2, RoundingMode.HALF_UP).toPlainString()}%)"
        }

        /** 한국 증권가 관행: 이익=빨강(pos), 손실=파랑(neg). */
        private fun RealizedPnl.css(): String =
            when {
                amount.signum() > 0 -> "pos"
                amount.signum() < 0 -> "neg"
                else -> "zero"
            }
    }
}

/** 실시간 화면의 보유 종목 한 줄. 수량·평단만 보여준다(현재가·평가손익은 아직 넣지 않기로 했다). */
data class HoldingLiveView(
    val market: String,
    val symbol: String,
    val symbolName: String,
    val quantity: Int,
    val avgCostText: String,
    val updatedAtText: String,
) {
    companion object {
        fun of(
            holding: AccountHolding,
            symbolName: String,
        ) = HoldingLiveView(
            market = holding.market.name,
            symbol = holding.symbol,
            symbolName = symbolName,
            quantity = holding.quantity,
            avgCostText = holding.avgCost.money(holding.market.currency),
            updatedAtText = TIME_FORMAT.format(holding.updatedAt.atZone(KST)),
        )
    }
}

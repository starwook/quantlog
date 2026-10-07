package com.quantlog.order

import com.quantlog.position.AccountHolding
import com.quantlog.position.OrderState
import com.quantlog.position.PortfolioSummary
import com.quantlog.position.RealizedPnl
import com.quantlog.position.Trade
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val KST: ZoneId = ZoneId.of("Asia/Seoul")
private val DATE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")

/** 원화는 소수점 없이, 그 외는 소수점 둘째 자리까지. */
internal fun BigDecimal.money(currency: String): String = String.format(Locale.US, "%,.${if (currency == "KRW") 0 else 2}f", this)

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
                timeText = DATE_TIME_FORMAT.format(trade.executedAt.atZone(KST)),
            )
        }

        private fun RealizedPnl.text(currency: String) = signedMoneyAndPercent(amount, percent, currency)

        private fun RealizedPnl.css() = pnlCss(amount)
    }
}

/** 실시간 화면의 보유 종목 한 줄. 수량·평단·현재가·평가손익. 현재가는 KIS 잔고 동기화(10초)가 가져온 값이다. */
data class HoldingLiveView(
    val market: String,
    val symbol: String,
    val symbolName: String,
    val quantity: Int,
    val avgCostText: String,
    val currentPriceText: String,
    /** 종목 평가금액(현재가 × 수량). */
    val valueText: String,
    /** 평가손익. 예: +3,000 (+1.10%) */
    val unrealizedText: String,
    val unrealizedCss: String,
) {
    companion object {
        /** [livePrice] 가 있으면(실시간 틱) 잔고 사본의 현재가 대신 쓴다. */
        fun of(
            holding: AccountHolding,
            symbolName: String,
            livePrice: BigDecimal? = null,
        ): HoldingLiveView {
            val currency = holding.market.currency
            val currentPrice = livePrice ?: holding.currentPrice
            val cost = holding.avgCost.multiply(BigDecimal(holding.quantity))
            val amount = currentPrice.multiply(BigDecimal(holding.quantity)).subtract(cost)
            val percent = if (cost.signum() > 0) amount.multiply(BigDecimal(100)).divide(cost, MathContext.DECIMAL64) else BigDecimal.ZERO
            return HoldingLiveView(
                market = holding.market.name,
                symbol = holding.symbol,
                symbolName = symbolName,
                quantity = holding.quantity,
                avgCostText = holding.avgCost.money(currency),
                currentPriceText = currentPrice.money(currency),
                valueText = currentPrice.multiply(BigDecimal(holding.quantity)).money(currency),
                unrealizedText = signedMoneyAndPercent(amount, percent, currency),
                unrealizedCss = pnlCss(amount),
            )
        }
    }
}

/** 부호 붙인 금액과 퍼센트. 예: +3,000 (+1.10%) */
internal fun signedMoneyAndPercent(
    amount: BigDecimal,
    percent: BigDecimal,
    currency: String,
): String {
    val sign = if (amount.signum() > 0) "+" else ""
    val pct = percent.setScale(2, RoundingMode.HALF_UP)
    return "$sign${amount.money(currency)} (${if (pct.signum() > 0) "+" else ""}${pct.toPlainString()}%)"
}

/** 한국 증권가 관행: 이익=빨강(pos), 손실=파랑(neg). */
internal fun pnlCss(amount: BigDecimal): String =
    when {
        amount.signum() > 0 -> "pos"
        amount.signum() < 0 -> "neg"
        else -> "zero"
    }

/** 화면 맨 위 손익 요약. 오늘 실현손익 / 현재 평가손익 / 오늘 승패·회수. */
data class PnlSummaryLiveView(
    val todayText: String,
    val todayCss: String,
    /** 보유 종목 전체의 현재 평가손익. */
    val unrealizedText: String,
    val unrealizedCss: String,
    /** 보유 종목 전체의 총 평가금액(현재가 × 수량 합계). */
    val totalValueText: String,
    val buyCount: Int,
    val sellCount: Int,
    val win: Int,
    val loss: Int,
    val winRateText: String,
) {
    companion object {
        /** [unrealizedAmount]·[unrealizedPercent]·[totalValue] 는 실시간 현재가로 다시 계산한 값. */
        fun of(
            summary: PortfolioSummary,
            currency: String,
            unrealizedAmount: BigDecimal,
            unrealizedPercent: BigDecimal,
            totalValue: BigDecimal,
        ): PnlSummaryLiveView {
            val decided = summary.sellWinToday + summary.sellLossToday
            return PnlSummaryLiveView(
                todayText = signedMoneyAndPercent(summary.realizedPnlToday, summary.realizedPnlTodayPercent, currency),
                todayCss = pnlCss(summary.realizedPnlToday),
                unrealizedText = signedMoneyAndPercent(unrealizedAmount, unrealizedPercent, currency),
                unrealizedCss = pnlCss(unrealizedAmount),
                totalValueText = totalValue.money(currency),
                buyCount = summary.buyCountToday,
                sellCount = summary.sellCountToday,
                win = summary.sellWinToday,
                loss = summary.sellLossToday,
                winRateText = if (decided == 0) "-" else "${summary.sellWinToday * 100 / decided}%",
            )
        }
    }
}

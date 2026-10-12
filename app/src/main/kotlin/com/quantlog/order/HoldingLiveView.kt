package com.quantlog.order

import com.quantlog.common.money
import com.quantlog.common.pnlCss
import com.quantlog.common.signedMoneyAndPercent
import com.quantlog.position.AccountHolding
import java.math.BigDecimal
import java.math.MathContext

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

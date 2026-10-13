package com.quantlog.order

import com.quantlog.common.money
import com.quantlog.common.pnlCss
import com.quantlog.common.signedMoneyAndPercent
import com.quantlog.position.PortfolioSummary
import java.math.BigDecimal

/** 화면 맨 위 손익 요약. 오늘 실현손익 / 현재 평가손익 / 오늘 승패·회수. */
data class PnlSummaryLiveView(
    val todayText: String,
    val todayCss: String,
    /** 보유 종목 전체의 현재 평가손익. */
    val unrealizedText: String,
    val unrealizedCss: String,
    /** 보유 종목 전체의 총 평가금액(현재가 × 수량 합계). */
    val totalValueText: String,
    /** 지금 새 매수에 쓸 수 있는 돈(주문가능금액). 아직 모르면 "-". */
    val orderableText: String,
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
            orderable: BigDecimal?,
        ): PnlSummaryLiveView {
            val decided = summary.sellWinToday + summary.sellLossToday
            return PnlSummaryLiveView(
                todayText = signedMoneyAndPercent(summary.realizedPnlToday, summary.realizedPnlTodayPercent, currency),
                todayCss = pnlCss(summary.realizedPnlToday),
                unrealizedText = signedMoneyAndPercent(unrealizedAmount, unrealizedPercent, currency),
                unrealizedCss = pnlCss(unrealizedAmount),
                totalValueText = totalValue.money(currency),
                orderableText = orderable?.money(currency) ?: "-",
                buyCount = summary.buyCountToday,
                sellCount = summary.sellCountToday,
                win = summary.sellWinToday,
                loss = summary.sellLossToday,
                winRateText = if (decided == 0) "-" else "${summary.sellWinToday * 100 / decided}%",
            )
        }
    }
}

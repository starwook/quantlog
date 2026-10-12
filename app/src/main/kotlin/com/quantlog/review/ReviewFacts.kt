package com.quantlog.review

import com.quantlog.broker.Side
import com.quantlog.position.PortfolioSnapshot
import com.quantlog.position.Trade
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val KST: ZoneId = ZoneId.of("Asia/Seoul")
private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

/**
 * 리뷰 입력으로 쓰는 "사실" — 코드가 매매 기록에서 결정론적으로 계산한 숫자만 담는다. AI는 이걸 해석할 뿐 숫자를 만들지 않는다.
 * 체결가가 없는 주문(미체결·확인 전)은 손익·횟수에서 빠진다(없는 값을 지어내지 않는다).
 */
object ReviewFacts {
    /** [today] 하루치 체결 내역 전부 + 최근 [days]일 일별 요약 + 지금 보유 현황. */
    fun of(
        snapshot: PortfolioSnapshot,
        today: LocalDate,
        days: Int = 7,
    ): String {
        val filled = snapshot.trades.filter { it.filledPrice != null }
        val byDay = filled.groupBy { it.executedAt.atZone(KST).toLocalDate() }
        val todayTrades = byDay[today].orEmpty()

        return buildString {
            appendLine("## 오늘($today) 체결 ${todayTrades.size}건")
            if (todayTrades.isEmpty()) appendLine("(체결 없음)")
            todayTrades.forEach { appendLine(line(it, snapshot)) }

            appendLine()
            appendLine("## 최근 ${days}일 일별 요약 (체결된 주문 기준)")
            (0 until days).map { today.minusDays(it.toLong()) }.forEach { day ->
                val trades = byDay[day].orEmpty()
                val sells = trades.filter { it.side == Side.SELL }
                val pnls = sells.mapNotNull { trade -> trade.id?.let { snapshot.realizedPnlByTradeId[it] } }
                val amount = pnls.fold(BigDecimal.ZERO) { sum, pnl -> sum + pnl.amount }
                val wins = pnls.count { it.amount > BigDecimal.ZERO }
                appendLine(
                    "- $day: 매수 ${trades.count { it.side == Side.BUY }}건, 매도 ${sells.size}건, " +
                        "실현손익 ${amount.toPlainString()} (이익 ${wins}건 / 손실·본전 ${pnls.size - wins}건)",
                )
            }

            appendLine()
            appendLine("## 지금 보유 현황 (통화별)")
            if (snapshot.summaryByCurrency.values.all { it.holdings.isEmpty() }) appendLine("(보유 없음)")
            snapshot.summaryByCurrency.values.forEach { summary ->
                summary.holdings.forEach {
                    val percent = it.unrealizedPnlPercent.setScale(2, RoundingMode.HALF_UP)
                    appendLine(
                        "- ${it.market} ${it.symbol} ${it.quantity}주, 평단 ${it.avgCost.toPlainString()}, " +
                            "현재가 ${it.currentPrice.toPlainString()}, 평가손익 ${it.unrealizedPnl.toPlainString()} ($percent%)",
                    )
                }
                if (summary.holdings.isNotEmpty()) {
                    val unrealized = summary.unrealizedPnl.toPlainString()
                    val realized = summary.realizedPnlTotal.toPlainString()
                    appendLine("- ${summary.currency} 합계: 평가손익 $unrealized, 누적 실현손익 $realized")
                }
            }
        }.trimEnd()
    }

    private fun line(
        trade: Trade,
        snapshot: PortfolioSnapshot,
    ): String {
        val pnl = trade.id?.let { snapshot.realizedPnlByTradeId[it] }
        val result =
            pnl?.let { " → 실현손익 ${it.amount.toPlainString()} (${it.percent.setScale(2, RoundingMode.HALF_UP)}%)" }.orEmpty()
        return "- ${trade.executedAt.atZone(KST).format(TIME)} ${trade.side} ${trade.market} ${trade.symbol} " +
            "${trade.filledQuantity ?: trade.quantity}주 @ ${trade.filledPrice?.toPlainString()} — 사유: ${trade.reason ?: "—"}$result"
    }
}

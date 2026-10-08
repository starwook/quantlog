package com.quantlog.realized

import com.quantlog.broker.Market
import com.quantlog.broker.Side
import com.quantlog.position.PortfolioSnapshot
import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

private val KST: ZoneId = ZoneId.of("Asia/Seoul")
private val HUNDRED = BigDecimal(100)

/** 실현손익 금액과 그 기준이 된 매수 원가. 수익률(%)은 둘로 구한다. */
data class PnlSum(val amount: BigDecimal, val cost: BigDecimal) {
    val percent: BigDecimal
        get() = if (cost.signum() > 0) amount.multiply(HUNDRED).divide(cost, MathContext.DECIMAL64) else BigDecimal.ZERO

    operator fun plus(other: PnlSum) = PnlSum(amount + other.amount, cost + other.cost)

    companion object {
        val ZERO = PnlSum(BigDecimal.ZERO, BigDecimal.ZERO)
    }
}

data class SymbolPnl(val market: Market, val symbol: String, val sum: PnlSum)

data class DayPnl(val date: LocalDate, val sum: PnlSum, val symbols: List<SymbolPnl>)

/** 한 통화 기준의 한 달 실현손익. 통화가 다르면 합산하지 않는다. */
data class CurrencyRealizedReport(
    val currency: String,
    val total: PnlSum,
    /** 최근 날짜가 먼저. */
    val days: List<DayPnl>,
    /** 달 전체를 종목별로 합친 것. 손익이 큰 종목이 먼저. */
    val symbols: List<SymbolPnl>,
)

object RealizedPnlReport {
    private data class Entry(val date: LocalDate, val market: Market, val symbol: String, val sum: PnlSum)

    /** 이미 확정된 매도별 실현손익([PortfolioSnapshot.realizedPnlByTradeId])을 [month](KST) 안에서 일별·종목별로 묶는다. */
    fun of(
        snapshot: PortfolioSnapshot,
        month: YearMonth,
    ): List<CurrencyRealizedReport> {
        val entries =
            snapshot.trades.mapNotNull { trade ->
                if (trade.side != Side.SELL) return@mapNotNull null
                val pnl = trade.id?.let { snapshot.realizedPnlByTradeId[it] } ?: return@mapNotNull null
                val date = trade.executedAt.atZone(KST).toLocalDate()
                if (YearMonth.from(date) != month) return@mapNotNull null
                Entry(date, trade.market, trade.symbol, PnlSum(pnl.amount, pnl.avgBuyPrice.multiply(BigDecimal(pnl.matchedQuantity))))
            }

        return entries
            .groupBy { it.market.currency }
            .map { (currency, rows) ->
                CurrencyRealizedReport(
                    currency = currency,
                    total = rows.sum(),
                    days =
                        rows.groupBy { it.date }
                            .map { (date, dayRows) -> DayPnl(date, dayRows.sum(), dayRows.bySymbol()) }
                            .sortedByDescending { it.date },
                    symbols = rows.bySymbol(),
                )
            }
            .sortedBy { it.currency }
    }

    private fun List<Entry>.sum(): PnlSum = fold(PnlSum.ZERO) { acc, e -> acc + e.sum }

    private fun List<Entry>.bySymbol(): List<SymbolPnl> =
        groupBy { it.market to it.symbol }
            .map { (key, rows) -> SymbolPnl(key.first, key.second, rows.sum()) }
            .sortedByDescending { it.sum.amount }
}

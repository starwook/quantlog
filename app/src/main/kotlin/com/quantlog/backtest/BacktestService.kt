package com.quantlog.backtest

import com.quantlog.broker.MinuteCandle
import com.quantlog.strategy.EntrySignal
import com.quantlog.strategy.StrategyProperties
import com.quantlog.strategy.SupportBounceEntryRule
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalTime

data class SimulatedTrade(
    val entryTime: LocalTime,
    val entryPrice: BigDecimal,
    val exitTime: LocalTime,
    val exitPrice: BigDecimal,
    val returnPercent: BigDecimal,
    val exitReason: String,
)

data class BacktestResult(val candleCount: Int, val trades: List<SimulatedTrade>) {
    val winCount: Int get() = trades.count { it.returnPercent > BigDecimal.ZERO }
    val lossCount: Int get() = trades.count { it.returnPercent < BigDecimal.ZERO }
    val totalReturnPercent: BigDecimal get() = trades.fold(BigDecimal.ZERO) { acc, t -> acc.add(t.returnPercent) }
    val winRatePercent: BigDecimal?
        get() =
            if (trades.isEmpty()) {
                null
            } else {
                BigDecimal(winCount).multiply(BigDecimal(100)).divide(BigDecimal(trades.size), 1, RoundingMode.HALF_UP)
            }
}

/**
 * 실제 매매 없이, 쌓인 분봉으로 "이 전략대로 했으면 얼마 벌었을까"를 재생한다. 라이브(EntryScheduler)와
 * 똑같은 SupportBounceEntryRule·익절손절 % 를 그대로 재사용해서 로직이 어긋나지 않게 한다.
 * 미래 분봉을 미리 보지 않는다 — 매 시점 그때까지 쌓인 분봉만으로 판단한다(라이브와 동일 조건).
 * 청산가는 호가 단위 반올림 없이 순수 %로만 계산한다(참고용 근사치, 실제 체결가와 약간 다를 수 있음).
 */
@Service
class BacktestService(
    private val entryRule: SupportBounceEntryRule,
    private val strategyProperties: StrategyProperties,
) {
    fun simulate(candlesAscending: List<MinuteCandle>): BacktestResult {
        val trades = mutableListOf<SimulatedTrade>()
        var entryTime: LocalTime? = null
        var entryPrice: BigDecimal? = null

        candlesAscending.forEachIndexed { index, candle ->
            val price = entryPrice
            if (price == null) {
                val window = candlesAscending.subList(0, index + 1)
                if (entryRule.evaluate(window) == EntrySignal.BUY) {
                    entryTime = candle.time
                    entryPrice = candle.close
                }
                return@forEachIndexed
            }

            val returnPercent = percentChange(price, candle.close)
            when {
                returnPercent >= strategyProperties.takeProfitPercent -> {
                    trades += SimulatedTrade(entryTime!!, price, candle.time, candle.close, returnPercent, "익절")
                    entryTime = null
                    entryPrice = null
                }
                strategyProperties.stopLossPercent?.let { returnPercent <= it.negate() } == true -> {
                    trades += SimulatedTrade(entryTime!!, price, candle.time, candle.close, returnPercent, "손절")
                    entryTime = null
                    entryPrice = null
                }
            }
        }

        val openEntryTime = entryTime
        val openEntryPrice = entryPrice
        if (openEntryTime != null && openEntryPrice != null && candlesAscending.isNotEmpty()) {
            val last = candlesAscending.last()
            trades += SimulatedTrade(openEntryTime, openEntryPrice, last.time, last.close, percentChange(openEntryPrice, last.close), "미청산")
        }

        return BacktestResult(candlesAscending.size, trades)
    }

    private fun percentChange(
        from: BigDecimal,
        to: BigDecimal,
    ): BigDecimal = to.subtract(from).divide(from, 6, RoundingMode.HALF_UP).multiply(BigDecimal(100))
}

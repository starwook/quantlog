package com.quantlog.indicator

import com.quantlog.broker.MinuteCandle
import com.quantlog.common.KST
import org.ta4j.core.BarSeries
import org.ta4j.core.BaseBarSeries
import org.ta4j.core.Indicator
import org.ta4j.core.indicators.EMAIndicator
import org.ta4j.core.indicators.MACDIndicator
import org.ta4j.core.indicators.RSIIndicator
import org.ta4j.core.indicators.SMAIndicator
import org.ta4j.core.indicators.bollinger.BollingerBandsLowerIndicator
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator
import org.ta4j.core.indicators.bollinger.BollingerBandsUpperIndicator
import org.ta4j.core.indicators.helpers.ClosePriceIndicator
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator
import org.ta4j.core.num.Num
import kotlin.math.sqrt

/**
 * 분봉 목록(오래된 것부터) 위에서 지표 값을 꺼내 쓰는 곳. 같은 지표는 한 번만 만든다.
 * ta4j 지표는 i 번째 값을 구할 때 i 이전 봉만 본다 — 백테스트에서 미래 봉을 미리 보지 않는다.
 */
class IndicatorContext(val candles: List<MinuteCandle>) {
    private val series: BarSeries =
        BaseBarSeries("candles").also { series ->
            candles.forEach {
                // 분봉 시각은 그 1분의 시작이라 끝 시각(+1분)으로 넣는다. 날이 바뀌어도 시각은 계속 늘어난다.
                val end = it.date.atTime(it.time).plusMinutes(1).atZone(KST)
                series.addBar(end, it.open, it.high, it.low, it.close, it.volume)
            }
        }
    private val close = ClosePriceIndicator(series)
    private val cache = HashMap<String, Indicator<Num>>()

    val size: Int get() = candles.size

    fun close(i: Int): Double = candles[i].close.toDouble()

    fun sma(
        period: Int,
        i: Int,
    ): Double = cached("sma:$period") { SMAIndicator(close, period) }.at(i)

    fun rsi(
        period: Int,
        i: Int,
    ): Double = cached("rsi:$period") { RSIIndicator(close, period) }.at(i)

    fun macd(
        fast: Int,
        slow: Int,
        i: Int,
    ): Double = macdLine(fast, slow).at(i)

    fun macdSignal(
        fast: Int,
        slow: Int,
        signal: Int,
        i: Int,
    ): Double = cached("macdSignal:$fast:$slow:$signal") { EMAIndicator(macdLine(fast, slow), signal) }.at(i)

    fun bollingerLower(
        period: Int,
        k: Double,
        i: Int,
    ): Double =
        cached(
            "bbLower:$period:$k",
        ) { BollingerBandsLowerIndicator(bollingerMiddle(period), stdDev(period), series.numOf(k)) }.at(i)

    fun bollingerUpper(
        period: Int,
        k: Double,
        i: Int,
    ): Double =
        cached(
            "bbUpper:$period:$k",
        ) { BollingerBandsUpperIndicator(bollingerMiddle(period), stdDev(period), series.numOf(k)) }.at(i)

    /** 직전 봉 대비 등락률(%). 첫 봉은 0. */
    fun changePercent(i: Int): Double = if (i == 0) 0.0 else (close(i) / close(i - 1) - 1) * 100

    /** 최근 period 봉의 1봉 수익률(%) 표준편차 — 한투 "변동성"과 같은 정의(일봉 대신 분봉). */
    fun returnVolatility(
        period: Int,
        i: Int,
    ): Double {
        val changes = (i - period + 1..i).map { changePercent(it) }
        val mean = changes.average()
        return sqrt(changes.sumOf { (it - mean) * (it - mean) } / (period - 1))
    }

    /** i 까지 연속으로 종가가 오른(up) / 내린 봉 수. */
    fun consecutive(
        i: Int,
        up: Boolean,
    ): Int {
        var count = 0
        var j = i
        while (j > 0 && (if (up) close(j) > close(j - 1) else close(j) < close(j - 1))) {
            count++
            j--
        }
        return count
    }

    /** i 와 같은 날짜의 봉 중 i 이전(i 제외) 최고가. 그날 첫 봉이면 null. */
    fun dayHighBefore(i: Int): Double? {
        val date = candles[i].date
        var j = i - 1
        var high: Double? = null
        while (j >= 0 && candles[j].date == date) {
            high = maxOf(high ?: candles[j].high.toDouble(), candles[j].high.toDouble())
            j--
        }
        return high
    }

    /** i 까지 그날의 (종가 − 저가) / (고가 − 저가). 고가 = 저가면 null. 한투 "강한 종가"(IBS)를 당일 누적 범위로 본 것. */
    fun dayRangePosition(i: Int): Double? {
        val date = candles[i].date
        var high = candles[i].high.toDouble()
        var low = candles[i].low.toDouble()
        var j = i - 1
        while (j >= 0 && candles[j].date == date) {
            high = maxOf(high, candles[j].high.toDouble())
            low = minOf(low, candles[j].low.toDouble())
            j--
        }
        return if (high == low) null else (close(i) - low) / (high - low)
    }

    /** [from, to] 봉의 최고가. */
    fun highest(
        from: Int,
        to: Int,
    ): Double = (from..to).maxOf { candles[it].high.toDouble() }

    private fun macdLine(
        fast: Int,
        slow: Int,
    ): Indicator<Num> = cached("macd:$fast:$slow") { MACDIndicator(close, fast, slow) }

    private fun bollingerMiddle(period: Int) =
        cached("bbMiddle:$period") { BollingerBandsMiddleIndicator(SMAIndicator(close, period)) } as BollingerBandsMiddleIndicator

    private fun stdDev(period: Int) = cached("std:$period") { StandardDeviationIndicator(close, period) }

    private fun cached(
        key: String,
        create: () -> Indicator<Num>,
    ): Indicator<Num> = cache.getOrPut(key, create)

    private fun Indicator<Num>.at(i: Int): Double = getValue(i).doubleValue()
}

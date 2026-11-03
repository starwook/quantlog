package com.quantlog.indicator

import com.quantlog.broker.MinuteCandle
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IndicatorEvaluatorTest {
    private val date = LocalDate.of(2026, 11, 4)

    private fun candles(
        vararg closes: Number,
        day: LocalDate = date,
    ) = closes.mapIndexed { i, close ->
        val c = BigDecimal(close.toString())
        MinuteCandle(day, LocalTime.of(9, 0).plusMinutes(i.toLong()), c, c, c, c, 100)
    }

    private fun golden(
        fast: Int,
        slow: Int,
    ) = PresetParams.of(IndicatorPreset.GOLDEN_CROSS, mapOf("fast" to BigDecimal(fast), "slow" to BigDecimal(slow)))

    @Test
    fun `이동평균은 i 번째까지의 종가 평균이다`() {
        val context = IndicatorContext(candles(10, 20, 30, 40))
        assertEquals(30.0, context.sma(3, 3), 1e-9)
        assertEquals(25.0, context.sma(2, 1 + 1), 1e-9)
    }

    @Test
    fun `골든크로스는 단기가 장기를 뚫는 봉에서만 한 번 매수하고 반대로 뚫을 때 매도한다`() {
        // 내리다가(단기 < 장기) 오르고(교차) 계속 오르다가 다시 내린다.
        val series = candles(10, 9, 8, 7, 8, 10, 12, 13, 14, 12, 9, 7, 6)
        val timeline = IndicatorEvaluator.timeline(IndicatorPreset.GOLDEN_CROSS, golden(2, 4), series)
        val actions = series.indices.map { timeline.actionAt(it) }

        assertEquals(1, actions.count { it == IndicatorAction.BUY }, "조건이 계속 참이어도 다시 사지 않는다: $actions")
        assertEquals(1, actions.count { it == IndicatorAction.SELL }, "$actions")
        assertTrue(actions.indexOf(IndicatorAction.BUY) < actions.indexOf(IndicatorAction.SELL))
    }

    @Test
    fun `봉이 모자라면 상태가 없고, 처음 보는 순간 이미 참인 조건으로는 사지 않는다`() {
        // 처음부터 계속 오르는 시리즈: 판정 가능한 첫 봉에서 이미 단기 > 장기다.
        val series = candles(1, 2, 3, 4, 5, 6, 7, 8)
        val timeline = IndicatorEvaluator.timeline(IndicatorPreset.GOLDEN_CROSS, golden(2, 4), series)

        assertNull(timeline.states[2], "장기 MA(4봉)가 안 차면 판정하지 않는다")
        assertTrue(timeline.states[3]!!.buy)
        assertTrue(series.indices.none { timeline.actionAt(it) == IndicatorAction.BUY })
    }

    @Test
    fun `파라미터는 빠지면 기본값, 범위를 벗어나면 범위 끝으로 맞춘다`() {
        val params = PresetParams.of(IndicatorPreset.RSI_REBOUND, mapOf("period" to BigDecimal(1000), "low" to null))
        assertEquals(60, params.int("period"))
        assertEquals(30, params.int("low"))
        assertEquals(70, params.int("high"))
    }

    @Test
    fun `RSI 과매도 반등은 RSI 가 기준을 위로 넘는 봉에서 매수한다`() {
        val falling = (0 until 20).map { 100 - it * 2 }
        val rising = (1..10).map { 62 + it * 3 }
        val series = candles(*(falling + rising).toTypedArray())
        val params = PresetParams.of(IndicatorPreset.RSI_REBOUND, mapOf("period" to BigDecimal(5)))
        val timeline = IndicatorEvaluator.timeline(IndicatorPreset.RSI_REBOUND, params, series)

        val buyAt = series.indices.first { timeline.actionAt(it) == IndicatorAction.BUY }
        assertTrue(buyAt >= falling.size, "내리는 동안엔 RSI 가 기준 아래라 사지 않는다 (buyAt=$buyAt)")
        assertTrue(timeline.states[buyAt]!!.reason.startsWith("RSI5"))
    }

    @Test
    fun `모든 프리셋은 미래 봉을 보지 않는다 - 앞부분만 넣어도 같은 판정이 나온다`() {
        val previousDay = date.minusDays(1)
        val series =
            (0 until 150).map { i ->
                val close = BigDecimal(10_000 + (sin(i / 7.0) * 120 + i * 3).toInt())
                val day = if (i < 60) previousDay else date
                val minute = if (i < 60) i else i - 60
                MinuteCandle(
                    day,
                    LocalTime.of(9, 0).plusMinutes(minute.toLong()),
                    close,
                    close.add(BigDecimal(15)),
                    close.subtract(BigDecimal(15)),
                    close,
                    100,
                )
            }
        IndicatorPreset.entries.forEach { preset ->
            val params = PresetParams.of(preset)
            val full = IndicatorEvaluator.timeline(preset, params, series)
            listOf(70, 100, 149).forEach { cut ->
                val prefix = IndicatorEvaluator.timeline(preset, params, series.subList(0, cut + 1))
                assertEquals(full.states[cut], prefix.states[cut], "$preset 가 $cut 번째 봉에서 미래 봉을 봤다")
            }
        }
    }

    @Test
    fun `매수 조건이 없는 프리셋과 매도 조건이 없는 프리셋을 구분한다`() {
        assertTrue(!IndicatorPreset.BREAKOUT_FAIL.hasBuy && IndicatorPreset.BREAKOUT_FAIL.hasSell)
        assertTrue(IndicatorPreset.INTRADAY_HIGH_BREAKOUT.hasBuy && !IndicatorPreset.INTRADAY_HIGH_BREAKOUT.hasSell)
        assertEquals(13, IndicatorPreset.entries.size)
    }
}

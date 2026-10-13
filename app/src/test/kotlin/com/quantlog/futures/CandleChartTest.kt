package com.quantlog.futures

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CandleChartTest {
    private fun candle(
        date: String,
        open: String,
        high: String,
        low: String,
        close: String,
    ) = Candle(date, null, BigDecimal(open), BigDecimal(high), BigDecimal(low), BigDecimal(close), 1)

    private val candles =
        listOf(candle("20261012", "100", "110", "90", "105"), candle("20261013", "105", "108", "95", "98"))

    @Test
    fun `높은 가격이 위에 그려지고 양봉 음봉을 구분한다`() {
        val chart = CandleChart(candles, emptyList())

        assertTrue(chart.bars[0].wickTop < chart.bars[0].wickBottom)
        assertTrue(chart.bars[0].up)
        assertTrue(!chart.bars[1].up)
        assertEquals("20261012", chart.from)
        assertEquals("20261013", chart.to)
    }

    @Test
    fun `차트 가격대에서 너무 먼 가격선은 그리지 않는다`() {
        val levels =
            listOf(
                KeyLevel("콜 최대 (저항)", BigDecimal("112"), null),
                KeyLevel("풋 최대 (지지)", BigDecimal("500"), null),
            )

        val chart = CandleChart(candles, levels)

        assertEquals(listOf("콜 최대 (저항)"), chart.lines.map { it.label })
        assertTrue(chart.lines.single().y < chart.bars[0].wickTop)
    }
}

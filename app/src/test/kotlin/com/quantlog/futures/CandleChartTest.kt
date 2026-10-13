package com.quantlog.futures

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals

class CandleChartTest {
    private fun candle(
        date: String,
        time: String?,
    ) = Candle(date, time, BigDecimal("100"), BigDecimal("110"), BigDecimal("90"), BigDecimal("105"), 7)

    @Test
    fun `일봉은 날짜 문자열을 시간으로 쓴다`() {
        val chart = CandleChart(listOf(candle("20261012", null), candle("20261013", null)), emptyList())

        assertEquals(listOf<Any>("2026-10-12", "2026-10-13"), chart.points.map { it.time })
        assertEquals("20261012", chart.from)
        assertEquals("20261013", chart.to)
    }

    @Test
    fun `분봉은 한국 시각을 UTC 초로 넘겨 브라우저에 한국 시각으로 보이게 한다`() {
        val chart = CandleChart(listOf(candle("20261014", "020100")), emptyList())

        // 2026-10-14T02:01:00 을 UTC 로 본 초.
        assertEquals(1_791_943_260L, chart.points.single().time)
        assertEquals("20261014 02:01", chart.to)
    }

    @Test
    fun `가격선은 라벨과 가격을 그대로 넘긴다`() {
        val chart = CandleChart(listOf(candle("20261013", null)), listOf(KeyLevel("콜 최대 (저항)", BigDecimal("112.5"), null)))

        assertEquals(listOf(ChartLine("콜 최대 (저항)", 112.5)), chart.lines)
    }
}

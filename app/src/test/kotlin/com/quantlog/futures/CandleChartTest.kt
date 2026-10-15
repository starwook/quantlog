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

    private fun minute(
        date: String,
        time: String,
        close: String,
    ) = Candle(date, time, BigDecimal(close), BigDecimal(close), BigDecimal(close), BigDecimal(close), 1)

    @Test
    fun `일봉 기준은 직전 봉 종가다`() {
        val chart = CandleChart(listOf(candle("20261012", null), candle("20261013", null)), emptyList())

        assertEquals(105.0, chart.baseline)
        assertEquals("전일 종가", chart.baselineLabel)
    }

    @Test
    fun `야간 1분봉의 기준은 그날 주간 종가다`() {
        val chart =
            CandleChart(
                listOf(minute("20261013", "153000", "940"), minute("20261013", "154500", "942"), minute("20261014", "020100", "957")),
                emptyList(),
            )

        assertEquals(942.0, chart.baseline)
        assertEquals("주간 종가", chart.baselineLabel)
    }

    @Test
    fun `1분봉은 API 가 준 기준 종가를 우선하고 없을 때만 봉에서 찾는다`() {
        val withYesterday = listOf(minute("20261013", "154500", "942"), minute("20261014", "100000", "950"))
        assertEquals(900.0, CandleChart(withYesterday, emptyList(), BigDecimal("900")).baseline)
        assertEquals(942.0, CandleChart(withYesterday, emptyList()).baseline)

        val night = listOf(minute("20261013", "154500", "942"), minute("20261014", "020100", "957"))
        assertEquals(943.0, CandleChart(night, emptyList(), BigDecimal("943")).baseline)

        val onlyToday = listOf(minute("20261014", "100000", "950"))
        assertEquals(900.0, CandleChart(onlyToday, emptyList(), BigDecimal("900")).baseline)
    }

    @Test
    fun `06시 마감 봉도 야간으로 보고 그날 주간 종가를 기준으로 한다`() {
        val chart = CandleChart(listOf(minute("20261014", "154500", "940"), minute("20261015", "060000", "961")), emptyList())

        assertEquals(940.0, chart.baseline)
        assertEquals("주간 종가", chart.baselineLabel)
    }
}

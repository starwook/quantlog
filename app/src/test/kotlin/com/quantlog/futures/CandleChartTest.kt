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
    fun `정규장 1분봉의 기준은 전일 정규장 마지막 봉이고 없으면 전광판에서 구한 전일 종가를 쓴다`() {
        val withYesterday = listOf(minute("20261013", "154500", "942"), minute("20261014", "100000", "950"))
        assertEquals(942.0, CandleChart(withYesterday, emptyList(), BigDecimal("900")).baseline)

        val onlyToday = listOf(minute("20261014", "100000", "950"))
        assertEquals(900.0, CandleChart(onlyToday, emptyList(), BigDecimal("900")).baseline)
    }
}

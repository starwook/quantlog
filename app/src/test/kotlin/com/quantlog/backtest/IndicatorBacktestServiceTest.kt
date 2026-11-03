package com.quantlog.backtest

import com.quantlog.broker.MinuteCandle
import com.quantlog.indicator.IndicatorPreset
import com.quantlog.indicator.PresetParams
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IndicatorBacktestServiceTest {
    private val service = IndicatorBacktestService()
    private val date = LocalDate.of(2026, 11, 4)

    /** 종가만 주면 시가=고가=저가=종가인 봉. (high, low) 를 주면 그 봉만 꼬리가 생긴다. */
    private fun candles(
        closes: List<Number>,
        day: LocalDate = date,
        wicks: Map<Int, Pair<String, String>> = emptyMap(),
    ) = closes.mapIndexed { i, close ->
        val c = BigDecimal(close.toString())
        val (high, low) = wicks[i]?.let { BigDecimal(it.first) to BigDecimal(it.second) } ?: (c to c)
        MinuteCandle(day, LocalTime.of(9, 0).plusMinutes(i.toLong()), c, high, low, c, 100)
    }

    private fun params(
        takeProfit: String = "5",
        stopLoss: String? = null,
        sell: Boolean = false,
    ) = IndicatorBacktestParams(
        preset = IndicatorPreset.GOLDEN_CROSS,
        presetParams = PresetParams.of(IndicatorPreset.GOLDEN_CROSS, mapOf("fast" to BigDecimal(2), "slow" to BigDecimal(4))),
        takeProfitPercent = BigDecimal(takeProfit),
        stopLossPercent = stopLoss?.let { BigDecimal(it) },
        sellOnSignal = sell,
    )

    // 2/4 골든크로스는 5번째 봉(종가 100)에서 켜지고, 10번째 봉에서 데드크로스가 난다.
    private val crossUpThenDown = listOf(100, 99, 98, 97, 98, 100, 101, 102, 103, 101, 98, 97, 96)

    @Test
    fun `매수 신호 봉의 종가에 사고 익절가에 닿으면 판다`() {
        val result =
            service.simulateDay(
                date,
                emptyList(),
                candles(listOf(100, 99, 98, 97, 98, 100, 101, 104), wicks = mapOf(7 to ("106" to "103"))),
                params(takeProfit = "5"),
            )

        val trade = result.trades.single()
        assertEquals(LocalTime.of(9, 5), trade.entryTime)
        assertEquals(0, BigDecimal(100).compareTo(trade.entryPrice))
        assertEquals("익절", trade.exitReason)
        assertEquals(0, BigDecimal(105).compareTo(trade.exitPrice), "고가가 익절가(105)를 지나면 익절가에 판다")
        assertTrue(trade.entryReason.startsWith("매수 신호: MA2"))
    }

    @Test
    fun `시가가 이미 익절가 너머로 갭이면 시가에 판다`() {
        val result = service.simulateDay(date, emptyList(), candles(listOf(100, 99, 98, 97, 98, 100, 101, 107)), params(takeProfit = "5"))

        assertEquals(0, BigDecimal(107).compareTo(result.trades.single().exitPrice))
    }

    @Test
    fun `한 봉에서 손절과 익절이 다 닿으면 손절로 본다`() {
        val result =
            service.simulateDay(
                date,
                emptyList(),
                candles(listOf(100, 99, 98, 97, 98, 100, 100), wicks = mapOf(6 to ("110" to "90"))),
                params(takeProfit = "5", stopLoss = "2"),
            )

        assertEquals("손절", result.trades.single().exitReason)
        assertEquals(0, BigDecimal("98.00").compareTo(result.trades.single().exitPrice))
    }

    @Test
    fun `매도 신호는 옵션을 켰을 때만 판다`() {
        val off = service.simulateDay(date, emptyList(), candles(crossUpThenDown), params(takeProfit = "50", sell = false))
        val on = service.simulateDay(date, emptyList(), candles(crossUpThenDown), params(takeProfit = "50", sell = true))

        assertTrue(off.trades.single().exitReason.startsWith("미청산"))
        assertTrue(on.trades.single().exitReason.startsWith("매도 신호"))
        assertEquals(LocalTime.of(9, 10), on.trades.single().exitTime)
    }

    @Test
    fun `직전 거래일 분봉은 판정에만 쓰고 그날 매매하지 않는다`() {
        val previous = candles(listOf(100, 99, 98, 97, 98, 100, 101, 102), day = date.minusDays(1))
        // 오늘은 첫 봉부터 계속 오른다 — 직전 거래일에 이미 골든크로스가 났으니 오늘 새 신호는 없다.
        val today = candles(listOf(103, 104, 105, 106))

        val result = service.simulateDay(date, previous, today, params())

        assertEquals(4, result.candleCount)
        assertTrue(result.trades.isEmpty())
    }

    @Test
    fun `여러 날 합계는 수익률을 더하고 누적 고점에서 가장 깊이 빠진 폭을 최대 낙폭으로 본다`() {
        fun day(
            d: Int,
            vararg returns: String,
        ) = IndicatorDayResult(
            date.plusDays(d.toLong()),
            10,
            returns.map { IndicatorTrade(LocalTime.NOON, BigDecimal.ONE, LocalTime.NOON, BigDecimal.ONE, BigDecimal(it), "", "") },
        )

        val summary = service.summarize(listOf(day(2, "-3"), day(0, "2", "1"), day(1, "-1.5")))

        assertEquals(0, BigDecimal("-1.5").compareTo(summary.totalReturnPercent))
        assertEquals(0, BigDecimal("4.5").compareTo(summary.maxDrawdownPercent), "고점 +3 → 바닥 -1.5")
        assertEquals(4, summary.tradeCount)
        assertEquals(1, summary.winDays)
        assertEquals(0, BigDecimal("50.0").compareTo(summary.winRatePercent))
    }
}

package com.quantlog.backtest

import com.quantlog.broker.MinuteCandle
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MartingaleBacktestServiceTest {
    private val service = MartingaleBacktestService()
    private val date = LocalDate.of(2026, 10, 7)

    private fun candle(
        minute: Int,
        open: String,
        high: String,
        low: String,
        close: String,
    ) = MinuteCandle(
        date,
        LocalTime.of(9, 0).plusMinutes(minute.toLong()),
        BigDecimal(open),
        BigDecimal(high),
        BigDecimal(low),
        BigDecimal(close),
        100,
    )

    private fun params(
        drop: String = "0.5",
        tp: String = "1",
        stop: String? = "3",
        stages: Int = 5,
        reenter: Boolean = false,
    ) = MartingaleParams(BigDecimal(drop), 2, stages, BigDecimal(tp), stop?.let(::BigDecimal), 1, reenter)

    @Test
    fun `추가매수 없이 익절하면 시작 수량만큼 번다`() {
        val result = service.simulate(listOf(candle(0, "100", "101.5", "99.9", "101")), params())

        val cycle = result.cycles.single()
        assertEquals("익절", cycle.exitReason)
        assertEquals(1, cycle.stages)
        assertEquals(0, BigDecimal("1").compareTo(cycle.pnl))
    }

    @Test
    fun `하락하면 평단 기준으로 수량이 2배씩 늘고 평단 기준 익절한다`() {
        val candles =
            listOf(
                // 1주@100 → -0.5% 에서 1주 추가 (평단 99.75)
                candle(0, "100", "100", "99.5", "99.5"),
                // 평단 -0.5% = 99.2512... 닿아서 2주 추가
                candle(1, "99.5", "99.5", "99.25", "99.25"),
                candle(2, "99.25", "101", "99.25", "101"),
            )

        val cycle = service.simulate(candles, params(tp = "0.5")).cycles.single()

        assertEquals(3, cycle.stages)
        assertEquals(4, cycle.peakQuantity)
        assertEquals("익절", cycle.exitReason)
        assertTrue(cycle.pnl.signum() > 0)
    }

    @Test
    fun `최대 단계까지 샀는데 더 떨어지면 평단 기준 손절한다`() {
        val candles =
            listOf(
                // 최대 2단계: 1주@100, 1주@99.5 → 평단 99.75, 손절 -3% = 96.76
                candle(0, "100", "100", "90", "90"),
            )

        val cycle = service.simulate(candles, params(stages = 2)).cycles.single()

        assertEquals("손절", cycle.exitReason)
        assertEquals(2, cycle.stages)
        assertTrue(cycle.pnl.signum() < 0)
    }

    @Test
    fun `청산 안 된 채 장이 끝나면 마지막 종가로 미청산 처리한다`() {
        val result = service.simulate(listOf(candle(0, "100", "100.2", "99.9", "100.1")), params())

        assertEquals("미청산", result.cycles.single().exitReason)
    }

    @Test
    fun `재진입을 끄면 하루 한 사이클만 돌고 켜면 다음 분봉에 다시 산다`() {
        val candles =
            listOf(
                // 익절
                candle(0, "100", "101.5", "100", "101"),
                // 재진입 후 익절 (101 → 102.01)
                candle(1, "101", "102.5", "101", "102"),
            )

        assertEquals(1, service.simulate(candles, params(reenter = false)).cycles.size)
        assertEquals(2, service.simulate(candles, params(reenter = true)).cycles.size)
    }

    @Test
    fun `손절이 간격보다 작은 조합은 조합 후보에서 빠진다`() {
        val combos = SweepGrid().combos(params(stop = "0.6"))

        assertTrue(combos.isNotEmpty())
        assertTrue(combos.none { it.dropPercent > BigDecimal("0.6") })
    }
}

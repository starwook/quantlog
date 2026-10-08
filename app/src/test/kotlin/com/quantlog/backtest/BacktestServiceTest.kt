package com.quantlog.backtest

import com.quantlog.broker.MinuteCandle
import com.quantlog.strategy.EntryStrategyProperties
import com.quantlog.strategy.StrategyProperties
import com.quantlog.strategy.SupportBounceEntryRule
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BacktestServiceTest {
    private val date = LocalDate.of(2026, 9, 29)

    private fun candle(
        minute: Int,
        low: String,
        high: String,
        close: String,
    ) = MinuteCandle(
        date = date,
        time = LocalTime.of(9, 0).plusMinutes(minute.toLong()),
        open = BigDecimal(close),
        high = BigDecimal(high),
        low = BigDecimal(low),
        close = BigDecimal(close),
        volume = 100,
    )

    /** 0~14: 9,900원대 지지선 형성. 15: 지지선 근처 반등(진입). */
    private fun baselineWithEntry(): MutableList<MinuteCandle> {
        val candles = (0..13).map { candle(it, "9880", "9900", "9900") }.toMutableList()
        candles += candle(14, "9880", "9900", "9895")
        candles += candle(15, "9880", "9915", "9910") // 진입: 9910
        return candles
    }

    private fun service(
        takeProfit: String = "1",
        stopLoss: String = "1",
    ) = BacktestService(
        SupportBounceEntryRule(EntryStrategyProperties(minCandles = 15, nearSupportPercent = BigDecimal("0.3"))),
        StrategyProperties(BigDecimal(takeProfit), BigDecimal(stopLoss)),
    )

    @Test
    fun `분봉이 없으면 거래도 없다`() {
        val result = service().simulate(emptyList())
        assertEquals(0, result.candleCount)
        assertTrue(result.trades.isEmpty())
        assertEquals(null, result.winRatePercent)
    }

    @Test
    fun `진입 후 익절 목표가 도달하면 익절로 청산한다`() {
        val candles = baselineWithEntry()
        candles += candle(16, "9930", "9950", "9940") // 9910 대비 +0.30%
        candles += candle(17, "10000", "10020", "10010") // 9910 대비 +1.01% → 익절

        val result = service(takeProfit = "1").simulate(candles)

        val trade = result.trades.single()
        assertEquals("익절", trade.exitReason)
        assertEquals(1, result.winCount)
        assertTrue(trade.returnPercent >= BigDecimal("1"))
    }

    @Test
    fun `손절 목표가 도달하면 손절로 청산한다`() {
        val candles = baselineWithEntry()
        candles += candle(16, "9800", "9820", "9810")
        candles += candle(17, "9780", "9800", "9790") // 9910 대비 -1.21% → 손절

        val result = service(stopLoss = "1").simulate(candles)

        val trade = result.trades.single()
        assertEquals("손절", trade.exitReason)
        assertEquals(1, result.lossCount)
    }

    @Test
    fun `장 마감까지 청산 안 되면 미청산으로 마지막 종가에 정리한다`() {
        val candles = baselineWithEntry()
        candles += candle(16, "9900", "9920", "9905") // 목표가 안 닿음

        val result = service().simulate(candles)

        val trade = result.trades.single()
        assertEquals("미청산", trade.exitReason)
        assertEquals(LocalTime.of(9, 16), trade.exitTime)
    }

    @Test
    fun `신호가 없으면 거래도 없다`() {
        val flat = (0..20).map { candle(it, "9990", "9990", "9990") }
        val result = service().simulate(flat)
        assertTrue(result.trades.isEmpty())
    }
}

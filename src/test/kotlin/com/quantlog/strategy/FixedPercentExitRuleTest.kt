package com.quantlog.strategy

import com.quantlog.broker.Quote
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals

class FixedPercentExitRuleTest {
    private val avg = BigDecimal("100")
    private val rule = FixedPercentExitRule(BigDecimal("1"), BigDecimal("1"))

    private fun usd(price: String) = Quote(BigDecimal(price), BigDecimal("0.01"))

    @Test
    fun `초기값 +1퍼센트 이상이면 익절`() {
        assertEquals(ExitSignal.TAKE_PROFIT, rule.evaluate(avg, usd("101")))
        assertEquals(ExitSignal.TAKE_PROFIT, rule.evaluate(avg, usd("110")))
    }

    @Test
    fun `초기값 -1퍼센트 이하이면 손절`() {
        assertEquals(ExitSignal.STOP_LOSS, rule.evaluate(avg, usd("99")))
        assertEquals(ExitSignal.STOP_LOSS, rule.evaluate(avg, usd("90")))
    }

    @Test
    fun `범위 안이면 보유`() {
        assertEquals(ExitSignal.HOLD, rule.evaluate(avg, usd("100.99")))
        assertEquals(ExitSignal.HOLD, rule.evaluate(avg, usd("99.01")))
        assertEquals(ExitSignal.HOLD, rule.evaluate(avg, usd("100")))
    }

    @Test
    fun `익절과 손절 폭을 따로 지정할 수 있다`() {
        val custom = FixedPercentExitRule(takeProfitPercent = BigDecimal("3"), stopLossPercent = BigDecimal("2"))
        assertEquals(ExitSignal.HOLD, custom.evaluate(avg, usd("102.99")))
        assertEquals(ExitSignal.TAKE_PROFIT, custom.evaluate(avg, usd("103")))
        assertEquals(ExitSignal.STOP_LOSS, custom.evaluate(avg, usd("98")))
    }

    @Test
    fun `목표가는 평단 ±1퍼센트에 가장 가까운 호가로 잡는다`() {
        // 평단 272,000원, 호가 500원: +1% = 274,720 → 274,500 / -1% = 269,280 → 269,500
        val targets = rule.targets(BigDecimal("272000"), Quote(BigDecimal("271750"), BigDecimal("500")))
        assertEquals(0, BigDecimal("274500").compareTo(targets.takeProfitPrice))
        assertEquals(0, BigDecimal("269500").compareTo(targets.stopLossPrice!!))
    }

    @Test
    fun `손절을 보류하면 얼마나 떨어져도 보유한다`() {
        val noStop = FixedPercentExitRule(takeProfitPercent = BigDecimal("1"), stopLossPercent = null)
        assertEquals(ExitSignal.HOLD, noStop.evaluate(avg, usd("50")))
        assertEquals(ExitSignal.TAKE_PROFIT, noStop.evaluate(avg, usd("101")))
        assertEquals(null, noStop.targets(avg, usd("100")).stopLossPrice)
    }

    @Test
    fun `호가로 맞춘 목표가에 닿으면 판정한다`() {
        val avgKrw = BigDecimal("272000")
        assertEquals(ExitSignal.TAKE_PROFIT, rule.evaluate(avgKrw, Quote(BigDecimal("274500"), BigDecimal("500"))))
        assertEquals(ExitSignal.HOLD, rule.evaluate(avgKrw, Quote(BigDecimal("274000"), BigDecimal("500"))))
        assertEquals(ExitSignal.STOP_LOSS, rule.evaluate(avgKrw, Quote(BigDecimal("269500"), BigDecimal("500"))))
    }
}

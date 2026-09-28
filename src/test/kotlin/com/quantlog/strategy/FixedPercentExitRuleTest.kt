package com.quantlog.strategy

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals

class FixedPercentExitRuleTest {
    private val avg = BigDecimal("100")

    @Test
    fun `초기값 +1퍼센트 이상이면 익절`() {
        val rule = FixedPercentExitRule(BigDecimal("1"), BigDecimal("1"))
        assertEquals(ExitSignal.TAKE_PROFIT, rule.evaluate(avg, BigDecimal("101")))
        assertEquals(ExitSignal.TAKE_PROFIT, rule.evaluate(avg, BigDecimal("110")))
    }

    @Test
    fun `초기값 -1퍼센트 이하이면 손절`() {
        val rule = FixedPercentExitRule(BigDecimal("1"), BigDecimal("1"))
        assertEquals(ExitSignal.STOP_LOSS, rule.evaluate(avg, BigDecimal("99")))
        assertEquals(ExitSignal.STOP_LOSS, rule.evaluate(avg, BigDecimal("90")))
    }

    @Test
    fun `범위 안이면 보유`() {
        val rule = FixedPercentExitRule(BigDecimal("1"), BigDecimal("1"))
        assertEquals(ExitSignal.HOLD, rule.evaluate(avg, BigDecimal("100.99")))
        assertEquals(ExitSignal.HOLD, rule.evaluate(avg, BigDecimal("99.01")))
        assertEquals(ExitSignal.HOLD, rule.evaluate(avg, avg))
    }

    @Test
    fun `익절과 손절 폭을 따로 지정할 수 있다`() {
        val rule = FixedPercentExitRule(takeProfitPercent = BigDecimal("3"), stopLossPercent = BigDecimal("2"))
        assertEquals(ExitSignal.HOLD, rule.evaluate(avg, BigDecimal("102.99")))
        assertEquals(ExitSignal.TAKE_PROFIT, rule.evaluate(avg, BigDecimal("103")))
        assertEquals(ExitSignal.STOP_LOSS, rule.evaluate(avg, BigDecimal("98")))
    }
}

package com.quantlog.strategy

import com.quantlog.broker.Quote
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MartingaleEntryRuleTest {
    private val rule = MartingaleEntryRule(MartingaleProperties())

    private fun krw(price: String) = Quote(BigDecimal(price), BigDecimal("500"))

    @Test
    fun `트리거 가격은 직전 매수가 -1퍼센트에 가장 가까운 호가`() {
        // 272,000 × 0.99 = 269,280 → 269,500
        assertEquals(0, BigDecimal("269500").compareTo(rule.triggerPrice(BigDecimal("272000"), krw("270000"))))
    }

    @Test
    fun `트리거에 닿으면 직전 수량의 2배`() {
        assertEquals(2, rule.nextQuantity(BigDecimal("272000"), 1, krw("269500")))
        assertEquals(16, rule.nextQuantity(BigDecimal("272000"), 8, krw("260000")))
    }

    @Test
    fun `트리거 위면 사지 않는다`() {
        assertNull(rule.nextQuantity(BigDecimal("272000"), 1, krw("270000")))
    }
}

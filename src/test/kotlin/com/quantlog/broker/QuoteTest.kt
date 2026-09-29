package com.quantlog.broker

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.test.assertEquals

class QuoteTest {
    private val samsung = Quote(BigDecimal("273000"), BigDecimal("500"))

    @Test
    fun `국내 가격을 호가 단위 배수로 맞춘다`() {
        // 2026-09-29 호가단위 오류로 거부됐던 274,365원 주문
        assertEquals(0, BigDecimal("274500").compareTo(samsung.roundToTick(BigDecimal("274365"))))
        assertEquals(0, BigDecimal("274000").compareTo(samsung.roundToTick(BigDecimal("274365"), RoundingMode.FLOOR)))
        assertEquals(0, BigDecimal("274500").compareTo(samsung.roundToTick(BigDecimal("274001"), RoundingMode.CEILING)))
    }

    @Test
    fun `미국은 1달러 이상이면 센트 단위`() {
        val quote = Quote(BigDecimal("187.50"), Market.NASDAQ.overseasTickSize(BigDecimal("187.50")))
        assertEquals(0, BigDecimal("189.38").compareTo(quote.roundToTick(BigDecimal("189.375"))))
    }
}

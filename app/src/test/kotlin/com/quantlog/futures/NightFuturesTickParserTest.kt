package com.quantlog.futures

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NightFuturesTickParserTest {
    private fun record(
        code: String,
        time: String,
        price: String,
        qty: String,
    ): String {
        val fields = MutableList(50) { "0" }
        fields[0] = code
        fields[1] = time
        fields[5] = price
        fields[9] = qty
        return fields.joinToString("^")
    }

    @Test
    fun `야간선물 체결 한 건을 읽는다`() {
        val ticks = NightFuturesTickParser.parse("0|H0MFCNT0|001|" + record("A01612", "020105", "941.85", "3"))

        assertEquals(listOf(NightFuturesTick("A01612", LocalTime.of(2, 1, 5), BigDecimal("941.85"), 3)), ticks)
    }

    @Test
    fun `주간선물 체결도 같은 칸 순서로 읽는다`() {
        val ticks = NightFuturesTickParser.parse("0|H0IFCNT0|001|" + record("A01612", "093001", "950.10", "2"))

        assertEquals(listOf(NightFuturesTick("A01612", LocalTime.of(9, 30, 1), BigDecimal("950.10"), 2)), ticks)
    }

    @Test
    fun `여러 건이 붙어 오면 건수대로 나눈다`() {
        val data = record("A01612", "020105", "941.85", "3") + "^" + record("A01612", "020106", "941.90", "1")

        assertEquals(2, NightFuturesTickParser.parse("0|H0MFCNT0|002|$data").size)
    }

    @Test
    fun `다른 TR 이나 깨진 메시지는 건너뛴다`() {
        assertTrue(NightFuturesTickParser.parse("0|H0STCNT0|001|005930^093001^70100").isEmpty())
        assertTrue(NightFuturesTickParser.parse("0|H0MFCNT0|001|A01612^abc").isEmpty())
    }
}

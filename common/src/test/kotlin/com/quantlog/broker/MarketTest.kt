package com.quantlog.broker

import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MarketTest {
    private val seoul = ZoneId.of("Asia/Seoul")

    private fun kst(
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
    ) = ZonedDateTime.of(2026, month, day, hour, minute, 0, 0, seoul)

    @Test
    fun `국내 정규장은 09시부터 15시20분 전까지`() {
        assertFalse(Market.KR.isRegularSession(kst(9, 29, 8, 59)))
        assertTrue(Market.KR.isRegularSession(kst(9, 29, 9, 0)))
        assertTrue(Market.KR.isRegularSession(kst(9, 29, 15, 19)))
        assertFalse(Market.KR.isRegularSession(kst(9, 29, 15, 20)))
    }

    @Test
    fun `주말은 장이 없다`() {
        assertFalse(Market.KR.isRegularSession(kst(10, 3, 10, 0))) // 토요일
    }

    @Test
    fun `국내는 주문가능시간과 정규장이 같다`() {
        assertTrue(Market.KR.isTradable(kst(9, 29, 9, 0)))
        assertFalse(Market.KR.isTradable(kst(9, 29, 15, 20)))
    }
}

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
    fun `미국 정규장은 서머타임이면 한국시간 22시30분부터`() {
        assertFalse(Market.NASDAQ.isRegularSession(kst(9, 29, 22, 29)))
        assertTrue(Market.NASDAQ.isRegularSession(kst(9, 29, 22, 30)))
        assertTrue(Market.NASDAQ.isRegularSession(kst(9, 30, 4, 59)))
        assertFalse(Market.NASDAQ.isRegularSession(kst(9, 30, 5, 0)))
    }

    @Test
    fun `서머타임이 끝나면 한국시간 23시30분부터`() {
        assertFalse(Market.NASDAQ.isRegularSession(kst(11, 10, 22, 30)))
        assertTrue(Market.NASDAQ.isRegularSession(kst(11, 10, 23, 30)))
    }

    @Test
    fun `국내는 주문가능시간과 정규장이 같다`() {
        assertTrue(Market.KR.isTradable(kst(9, 29, 9, 0)))
        assertFalse(Market.KR.isTradable(kst(9, 29, 15, 20)))
    }

    @Test
    fun `미국은 프리마켓부터 애프터마켓까지 주문 가능하다`() {
        // 서머타임(2026-09-29): 뉴욕 04:00 = 한국 17:00, 뉴욕 20:00 = 다음날 한국 09:00.
        assertFalse(Market.AMEX.isTradable(kst(9, 29, 16, 59)))
        assertTrue(Market.AMEX.isTradable(kst(9, 29, 17, 0))) // 프리마켓 시작
        assertTrue(Market.AMEX.isTradable(kst(9, 29, 22, 30))) // 정규장 중
        assertTrue(Market.AMEX.isTradable(kst(9, 30, 8, 59))) // 애프터마켓
        assertFalse(Market.AMEX.isTradable(kst(9, 30, 9, 0))) // 애프터마켓 종료
    }
}

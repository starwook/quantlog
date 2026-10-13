package com.quantlog.futures

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OptionBoardTest {
    private fun quote(oi: Long) = OptionQuote(BigDecimal("1.50"), oi, 0, 10)

    private fun strike(
        strike: String,
        callOi: Long,
        putOi: Long,
    ) = OptionStrike(BigDecimal(strike), quote(callOi), quote(putOi))

    @Test
    fun `행사가 오름차순으로 정렬하고 지수에 가장 가까운 행사가가 ATM 이다`() {
        val board = OptionBoard(listOf(strike("960.00", 1, 1), strike("940.00", 1, 1), strike("950.00", 1, 1)), BigDecimal("939.44"))

        assertEquals(listOf("940.00", "950.00", "960.00"), board.strikes.map { it.strike.toPlainString() })
        assertEquals(BigDecimal("940.00"), board.atmStrike)
    }

    @Test
    fun `콜 풋 최대 미결제 행사가가 매물대다`() {
        val board = OptionBoard(listOf(strike("340.00", 0, 800), strike("350.00", 100, 200), strike("360.00", 900, 0)), null)

        assertEquals(BigDecimal("360.00"), board.callWall?.strike)
        assertEquals(BigDecimal("340.00"), board.putWall?.strike)
        assertEquals(BigDecimal("1.00"), board.putCallRatio)
        assertNull(board.atmStrike)
    }

    @Test
    fun `맥스페인은 매수자 손실이 가장 큰 행사가다`() {
        // 콜 350에 100, 풋 350에 100 → 350 에서 만기되면 둘 다 가치가 없다.
        val board = OptionBoard(listOf(strike("340.00", 0, 0), strike("350.00", 100, 100), strike("360.00", 0, 0)), null)

        assertEquals(BigDecimal("350.00"), board.maxPain)
    }

    @Test
    fun `미결제약정이 없으면 매물대를 계산하지 않는다`() {
        val board = OptionBoard(listOf(strike("350.00", 0, 0)), null)

        assertNull(board.callWall)
        assertNull(board.maxPain)
        assertNull(board.putCallRatio)
    }

    @Test
    fun `만기일은 둘째 목요일이고 지나면 다음 월물이다`() {
        assertEquals(LocalDate.of(2026, 10, 8), OptionMaturity.expiry(YearMonth.of(2026, 10)))
        assertEquals(YearMonth.of(2026, 10), OptionMaturity.nearest(LocalDate.of(2026, 10, 8)))
        assertEquals(YearMonth.of(2026, 11), OptionMaturity.nearest(LocalDate.of(2026, 10, 13)))
    }

    @Test
    fun `옵션 코드는 1000 미만이면 행사가 정수부, 1000 이상이면 순번이다`() {
        val nov = YearMonth.of(2026, 11)

        assertEquals("B01611997", OptionCode.below1000(true, nov, BigDecimal("997.5")))
        assertEquals("C01611950", OptionCode.below1000(false, nov, BigDecimal("950.0")))
        assertEquals("B01611A01", OptionCode.from1000(true, nov, 1))
        assertEquals("B01611A99", OptionCode.from1000(true, nov, 99))
        assertEquals("C01611B01", OptionCode.from1000(false, nov, 100))
        assertEquals("B01701A01", OptionCode.from1000(true, YearMonth.of(2027, 1), 1))
    }
}

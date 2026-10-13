package com.quantlog.futures

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OptionBoardTest {
    private fun row(
        strike: String,
        oi: Long,
        change: Long = 0,
        atm: String = "",
    ): String {
        val fields = """"acpr":"$strike","optn_shrn_iscd":"2$strike","optn_prpr":"1.50","acml_vol":"10","atm_cls_name":"$atm""""
        return """{$fields,"hts_otst_stpl_qty":"$oi","otst_stpl_qty_icdc":"$change"}"""
    }

    private fun parse(
        calls: List<String>,
        puts: List<String>,
    ) = OptionBoard.parse(
        ObjectMapper().readTree("""{"rt_cd":"0","output1":[${calls.joinToString(",")}],"output2":[${puts.joinToString(",")}]}"""),
    )

    @Test
    fun `콜 풋을 행사가로 맞붙이고 정렬한다`() {
        val board = parse(listOf(row("360.00", 100), row("350.00", 300, atm = "ATM")), listOf(row("350.00", 500)))

        assertEquals(listOf("350.00", "360.00"), board.strikes.map { it.strike.toPlainString() })
        assertEquals(500, board.strikes[0].putOi)
        assertEquals(0, board.strikes[1].putOi)
        assertEquals(true, board.strikes[0].atm)
    }

    @Test
    fun `콜 풋 최대 미결제 행사가가 매물대다`() {
        val board = parse(listOf(row("350.00", 100), row("360.00", 900)), listOf(row("340.00", 800), row("350.00", 200)))

        assertEquals(BigDecimal("360.00"), board.callWall?.strike)
        assertEquals(BigDecimal("340.00"), board.putWall?.strike)
        assertEquals(BigDecimal("1.00"), board.putCallRatio)
    }

    @Test
    fun `맥스페인은 매수자 손실이 가장 큰 행사가다`() {
        // 콜 350에 100, 풋 350에 100 → 350 에서 만기되면 둘 다 가치가 없다.
        val board =
            parse(
                listOf(row("340.00", 0), row("350.00", 100), row("360.00", 0)),
                listOf(row("340.00", 0), row("350.00", 100), row("360.00", 0)),
            )

        assertEquals(BigDecimal("350.00"), board.maxPain)
    }

    @Test
    fun `미결제약정이 없으면 매물대를 계산하지 않는다`() {
        val board = parse(listOf(row("350.00", 0)), listOf(row("350.00", 0)))

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
}

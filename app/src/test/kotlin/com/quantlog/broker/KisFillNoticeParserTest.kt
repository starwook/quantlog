package com.quantlog.broker

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 게이트웨이가 원문 그대로 저장한 한투 체결통보를 앱이 문서대로 읽는 부분 ([KisFillNoticeParser]). */
class KisFillNoticeParserTest {
    /**
     * 국내 모의 실측(2026-10-07) 한 건 23필드 — 값은 실제 통보를 본떴다(식별 칸은 게이트웨이가 비운 그대로).
     * 접수 통보는 9·10 이 주문 수량·주문가이고 22 가 비며, 체결 통보는 9·10 이 체결 수량·체결단가이고 22 가 주문가다.
     */
    private fun domesticRow(
        side: String = "02",
        fill: Boolean = true,
        quantity: String = "0000000001",
        price: String = "000277500",
    ): String {
        val fields = MutableList(23) { "" }
        fields[2] = "0000008775"
        fields[4] = side
        fields[5] = "0"
        fields[6] = "00"
        fields[7] = "0"
        fields[8] = "005930"
        fields[9] = quantity
        fields[10] = price
        fields[11] = "092344"
        fields[12] = "0"
        fields[13] = if (fill) "2" else "1"
        fields[14] = if (fill) "2" else "1"
        fields[15] = "00950"
        fields[16] = "000000001"
        fields[18] = "1Y"
        fields[19] = "10"
        fields[21] = "삼성전자"
        fields[22] = if (fill) "000278500" else ""
        return fields.joinToString("^")
    }

    @Test
    fun `국내 체결 통보에서 체결수량 체결단가 주문가를 읽는다`() {
        val notice = KisFillNoticeParser.parse("H0STCNI9", domesticRow())!!

        assertEquals("005930", notice.symbol)
        assertEquals("0000008775", notice.orderNo)
        assertEquals(Side.BUY, notice.side)
        assertTrue(notice.isFill)
        assertEquals(0, notice.filledQuantity!!.compareTo(BigDecimal.ONE))
        assertEquals(0, notice.filledPrice!!.compareTo(BigDecimal(277500)))
        assertEquals(0, notice.orderPrice!!.compareTo(BigDecimal(278500)))
        assertEquals(0, notice.orderQuantity!!.compareTo(BigDecimal.ONE))
        assertEquals("092344", notice.time)
    }

    @Test
    fun `접수 통보의 수량 단가 칸은 체결이 아니라 주문값이라 체결 필드를 비운다`() {
        val notice = KisFillNoticeParser.parse("H0STCNI9", domesticRow(fill = false, price = "000278500"))!!

        assertFalse(notice.isFill)
        assertNull(notice.filledQuantity)
        assertNull(notice.filledPrice)
        assertEquals(0, notice.orderPrice!!.compareTo(BigDecimal(278500)))
    }

    @Test
    fun `01 은 매도 02 는 매수이고 모르는 코드는 null 이다`() {
        assertEquals(Side.SELL, KisFillNoticeParser.parse("H0STCNI9", domesticRow(side = "01"))!!.side)
        assertNull(KisFillNoticeParser.parse("H0STCNI9", domesticRow(side = "99"))!!.side)
    }

    @Test
    fun `모르는 TR 이거나 필드가 모자라면 null 이다`() {
        assertNull(KisFillNoticeParser.parse("H0GSCNI9", domesticRow()))
        assertNull(KisFillNoticeParser.parse("H0STCNI9", "a^b^c"))
    }
}

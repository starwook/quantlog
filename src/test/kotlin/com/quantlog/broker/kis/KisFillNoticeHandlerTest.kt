package com.quantlog.broker.kis

import com.fasterxml.jackson.databind.ObjectMapper
import com.quantlog.broker.FillNotice
import com.quantlog.broker.Side
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.math.BigDecimal
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KisFillNoticeHandlerTest {
    private val events = mutableListOf<Any>()
    private val handler = KisFillNoticeHandler(ApplicationEventPublisher { events += it })

    private val key = "0123456789abcdef0123456789abcdef"
    private val iv = "abcdef0123456789"

    private fun encrypt(text: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.toByteArray(), "AES"), IvParameterSpec(iv.toByteArray()))
        return Base64.getEncoder().encodeToString(cipher.doFinal(text.toByteArray()))
    }

    private fun subscribeResponse(trId: String) =
        ObjectMapper().readTree(
            """{"header":{"tr_id":"$trId"},"body":{"rt_cd":"0","msg1":"SUBSCRIBE SUCCESS","output":{"key":"$key","iv":"$iv"}}}""",
        )

    /**
     * 국내 모의 실측(2026-10-07) 한 건 23필드 — 값은 실제 통보를 본떴다(식별 필드는 빈 값).
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
    fun `암호화된 국내 체결 통보를 복호화해 체결수량 체결단가 주문가를 채운다`() {
        handler.onSubscribeResponse("H0STCNI9", subscribeResponse("H0STCNI9"))

        handler.onData("H0STCNI9", encrypted = true, recordCount = 1, payload = encrypt(domesticRow()))

        val notice = events.single() as FillNotice
        assertFalse(notice.overseas)
        assertEquals("005930", notice.symbol)
        assertEquals("0000008775", notice.orderNo)
        assertEquals(Side.BUY, notice.side)
        assertTrue(notice.isFill)
        assertEquals(0, notice.filledQuantity!!.compareTo(BigDecimal.ONE))
        assertEquals(0, notice.filledPrice!!.compareTo(BigDecimal(277500)))
        assertEquals(0, notice.orderPrice!!.compareTo(BigDecimal(278500)))
        assertEquals(0, notice.orderQuantity!!.compareTo(BigDecimal.ONE))
    }

    @Test
    fun `접수 통보의 수량 단가 칸은 체결이 아니라 주문값이라 체결 필드를 비운다`() {
        handler.onData("H0STCNI9", encrypted = false, recordCount = 1, payload = domesticRow(fill = false, price = "000278500"))

        val notice = events.single() as FillNotice
        assertFalse(notice.isFill)
        assertNull(notice.filledQuantity)
        assertNull(notice.filledPrice)
        assertEquals(0, notice.orderPrice!!.compareTo(BigDecimal(278500)))
    }

    @Test
    fun `01 은 매도 02 는 매수이고 모르는 코드는 null 이다`() {
        handler.onData("H0STCNI9", encrypted = false, recordCount = 1, payload = domesticRow(side = "01"))
        handler.onData("H0STCNI9", encrypted = false, recordCount = 1, payload = domesticRow(side = "99"))

        assertEquals(Side.SELL, (events[0] as FillNotice).side)
        assertNull((events[1] as FillNotice).side)
    }

    @Test
    fun `한 메시지에 여러 건이 이어 붙어 오면 건수로 나눠 건마다 발행한다`() {
        handler.onData(
            "H0STCNI9",
            encrypted = false,
            recordCount = 2,
            payload = domesticRow(fill = false) + "^" + domesticRow(fill = true),
        )

        assertEquals(2, events.size)
        assertFalse((events[0] as FillNotice).isFill)
        assertTrue((events[1] as FillNotice).isFill)
    }

    @Test
    fun `해외 체결통보는 25개 필드 위치로 읽는다`() {
        val fields = MutableList(25) { "" }
        fields[2] = "0030012345"
        fields[7] = "AAPL"
        fields[8] = "2"
        fields[9] = "189.5"
        fields[12] = "2"
        fields[15] = "2"
        handler.onSubscribeResponse("H0GSCNI9", subscribeResponse("H0GSCNI9"))

        handler.onData("H0GSCNI9", encrypted = true, recordCount = 1, payload = encrypt(fields.joinToString("^")))

        val notice = events.single() as FillNotice
        assertTrue(notice.overseas)
        assertEquals("AAPL", notice.symbol)
        assertEquals(0, notice.filledPrice!!.compareTo(BigDecimal("189.5")))
        assertNull(notice.side)
        assertTrue(notice.isFill)
    }

    @Test
    fun `암호화 키가 없거나 건수로 나눌 수 없거나 필드가 모자라면 발행하지 않는다`() {
        handler.onData("H0STCNI9", encrypted = true, recordCount = 1, payload = encrypt(domesticRow()))
        handler.onData("H0STCNI9", encrypted = false, recordCount = 1, payload = "a^b^c")
        handler.onData("H0STCNI9", encrypted = false, recordCount = 2, payload = domesticRow().substringBeforeLast("^"))

        assertTrue(events.isEmpty())
    }
}

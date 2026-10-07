package com.quantlog.broker.kis

import com.fasterxml.jackson.databind.ObjectMapper
import com.quantlog.broker.FillNotice
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    /** 국내 26개 필드: 위치만 의미 있는 곳(종목·주문번호·수량·단가·체결여부 등)만 채운다. */
    private fun domesticRow(
        filledFlag: String = "2",
        quantity: String = "3",
    ): String {
        val fields = MutableList(26) { "" }
        fields[2] = "0000012345"
        fields[3] = "0000000000"
        fields[4] = "02"
        fields[8] = "005930"
        fields[9] = quantity
        fields[10] = "273500"
        fields[11] = "093015"
        fields[12] = "N"
        fields[13] = filledFlag
        fields[14] = "Y"
        fields[16] = "5"
        return fields.joinToString("^")
    }

    @Test
    fun `암호화된 국내 체결통보를 복호화해 FillNotice 로 발행한다`() {
        handler.onSubscribeResponse("H0STCNI9", subscribeResponse("H0STCNI9"))

        handler.onData("H0STCNI9", encrypted = true, recordCount = 1, payload = encrypt(domesticRow()))

        val notice = events.single() as FillNotice
        assertFalse(notice.overseas)
        assertEquals("005930", notice.symbol)
        assertEquals("0000012345", notice.orderNo)
        assertEquals(0, notice.filledQuantity!!.compareTo(java.math.BigDecimal(3)))
        assertEquals(0, notice.filledPrice!!.compareTo(java.math.BigDecimal(273500)))
        assertEquals(0, notice.orderQuantity!!.compareTo(java.math.BigDecimal(5)))
        assertTrue(notice.isFill)
    }

    @Test
    fun `모의투자처럼 한 건이 23필드로 와도 건수로 나눠 앞쪽 필드를 읽는다`() {
        val fields = domesticRow().split("^").take(23)

        handler.onData("H0STCNI9", encrypted = false, recordCount = 1, payload = fields.joinToString("^"))

        val notice = events.single() as FillNotice
        assertEquals("005930", notice.symbol)
        assertTrue(notice.isFill)
    }

    @Test
    fun `체결여부 1 은 접수 통보라 체결로 보지 않는다`() {
        handler.onData("H0STCNI9", encrypted = false, recordCount = 1, payload = domesticRow(filledFlag = "1"))

        assertFalse((events.single() as FillNotice).isFill)
    }

    @Test
    fun `한 메시지에 여러 건이 이어 붙어 오면 건마다 발행한다`() {
        handler.onData(
            "H0STCNI9",
            encrypted = false,
            recordCount = 2,
            payload = domesticRow(quantity = "1") + "^" + domesticRow(quantity = "2"),
        )

        assertEquals(2, events.size)
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
        assertEquals(0, notice.filledPrice!!.compareTo(java.math.BigDecimal("189.5")))
        assertTrue(notice.isFill)
    }

    @Test
    fun `암호화 키가 없거나 필드 수가 안 맞으면 발행하지 않는다`() {
        handler.onData("H0STCNI9", encrypted = true, recordCount = 1, payload = encrypt(domesticRow()))
        handler.onData("H0STCNI9", encrypted = false, recordCount = 1, payload = "a^b^c")
        handler.onData("H0STCNI9", encrypted = false, recordCount = 2, payload = domesticRow().substringBeforeLast("^"))

        assertTrue(events.isEmpty())
    }
}

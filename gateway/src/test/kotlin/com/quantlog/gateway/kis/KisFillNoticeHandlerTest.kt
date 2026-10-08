package com.quantlog.gateway.kis

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals
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

    /** 국내 모의 실측(2026-10-07) 한 건 23필드 — 값은 실제 통보를 본떴다(식별 필드는 빈 값). */
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

    private fun bodies() = events.map { (it as BrokerNoticeReceived).body }

    @Test
    fun `암호화된 통보를 복호화해 필드를 해석하지 않고 원문 그대로 발행한다`() {
        handler.onSubscribeResponse("H0STCNI9", subscribeResponse("H0STCNI9"))

        handler.onData("H0STCNI9", encrypted = true, recordCount = 1, payload = encrypt(domesticRow()))

        val notice = events.single() as BrokerNoticeReceived
        assertEquals("H0STCNI9", notice.trId)
        assertEquals(domesticRow(), notice.body)
    }

    @Test
    fun `고객 ID 계좌번호 계좌명 칸은 비워서 발행한다`() {
        val fields = domesticRow().split("^").toMutableList()
        fields[0] = "고객"
        fields[1] = "50000000-01"
        fields[17] = "홍길동"

        handler.onData("H0STCNI9", encrypted = false, recordCount = 1, payload = fields.joinToString("^"))

        val body = bodies().single().split("^")
        assertEquals(listOf("", "", ""), listOf(body[0], body[1], body[17]))
        assertEquals("0000008775", body[2])
        assertEquals(23, body.size)
    }

    @Test
    fun `한 메시지에 여러 건이 이어 붙어 오면 건수로 나눠 건마다 발행한다`() {
        handler.onData(
            "H0STCNI9",
            encrypted = false,
            recordCount = 2,
            payload = domesticRow(fill = false) + "^" + domesticRow(fill = true),
        )

        assertEquals(listOf(domesticRow(fill = false), domesticRow(fill = true)), bodies())
    }

    @Test
    fun `암호화 키가 없거나 건수로 나눌 수 없으면 발행하지 않는다`() {
        handler.onData("H0STCNI9", encrypted = true, recordCount = 1, payload = encrypt(domesticRow()))
        // 23 필드를 2건으로는 나눌 수 없다
        handler.onData("H0STCNI9", encrypted = false, recordCount = 2, payload = domesticRow())

        assertTrue(events.isEmpty())
    }
}

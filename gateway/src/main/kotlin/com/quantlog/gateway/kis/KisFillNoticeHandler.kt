package com.quantlog.gateway.kis

import com.fasterxml.jackson.databind.JsonNode
import mu.KotlinLogging
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

private val log = KotlinLogging.logger {}

/** 증권사 체결통보(접수·체결·정정·취소·거부) 한 건의 원문. [body] 는 복호화한 `^` 구분 필드 그대로이고 계좌 식별 필드만 비워 둔다. */
data class BrokerNoticeReceived(val trId: String, val body: String)

/**
 * KIS 실시간 체결통보(국내 H0STCNI9, 모의) 수신 처리. 연결·구독은 [KisRealtimeClient] 가 같은 WebSocket 연결로 하고,
 * 여기는 (1) 구독 응답에 실려 오는 AES 키·IV 보관 (2) 수신 데이터 복호화 (3) 메시지 헤더의 건수로 한 건씩 나눠 [BrokerNoticeReceived] 발행만 한다.
 * **필드를 해석하지 않는다** — 어느 칸이 종목·수량인지는 앱이 한투 공식 문서(docs/kis-api/field-reference.md 3절)대로 읽는다(2026-10-08).
 * 예외는 보안: 고객 ID·계좌번호·계좌명 칸은 저장·로그 전에 비운다([IDENTIFYING_FIELDS]).
 */
@Component
class KisFillNoticeHandler(
    private val eventPublisher: ApplicationEventPublisher,
) {
    private data class AesKey(val key: String, val iv: String)

    private val keys = ConcurrentHashMap<String, AesKey>()

    /** 구독 응답(JSON). 성공이면 body.output 에 암호화 키·IV 가 들어 있다. */
    fun onSubscribeResponse(
        trId: String,
        node: JsonNode,
    ) {
        val body = node.path("body")
        val output = body.path("output")
        val key = output.path("key").asText("")
        val iv = output.path("iv").asText("")
        if (key.isNotBlank() && iv.isNotBlank()) keys[trId] = AesKey(key, iv)
        val result = body.path("rt_cd").asText()
        val message = body.path("msg1").asText()
        log.info { "[체결통보] 구독 응답 $trId: rt_cd=$result $message (키 수신=${keys.containsKey(trId)})" }
    }

    /** [payload] 는 `0|1 | TR_ID | 건수 | payload` 의 네 번째 칸, [recordCount] 는 세 번째 칸(건수). [encrypted] 는 맨 앞 글자가 "1" 인지. */
    fun onData(
        trId: String,
        encrypted: Boolean,
        recordCount: Int,
        payload: String,
    ) {
        val text =
            if (encrypted) {
                val aes = keys[trId] ?: return log.warn { "[체결통보] $trId 암호화 키가 없어 버린다" }
                runCatching { decrypt(aes.key, aes.iv, payload) }
                    .getOrElse { return log.warn(it) { "[체결통보] $trId 복호화 실패" } }
            } else {
                payload
            }
        val fields = text.split("^")
        // 한 메시지에 여러 건이 이어 붙어 올 수 있다(헤더의 건수). 한 건의 필드 수는 사양 문서가 아니라 실제 건수로 나눠서 구한다
        // (2026-10-07 모의 실측: 문서는 26개인데 H0STCNI9 가 23개로 옴). 나눠지지 않으면 식별 칸을 가릴 수 없어 저장하지 않는다.
        if (recordCount <= 0 || fields.size % recordCount != 0) {
            return log.error { "[체결통보] $trId 필드 수 ${fields.size} 를 건수 $recordCount 로 나눌 수 없어 버린다 — 앱의 체결 대조가 KIS 와 비교해 알린다" }
        }
        fields.chunked(fields.size / recordCount).forEach { record ->
            val body = record.mapIndexed { index, value -> if (index in IDENTIFYING_FIELDS) "" else value }.joinToString("^")
            log.info { "[체결통보] $trId $body" }
            eventPublisher.publishEvent(BrokerNoticeReceived(trId, body))
        }
    }

    /** AES-256-CBC, 키·IV 는 UTF-8 문자열 그대로, 데이터는 Base64, PKCS7 패딩(공식 샘플 kis_auth.py 와 같다). */
    internal fun decrypt(
        key: String,
        iv: String,
        cipherText: String,
    ): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.toByteArray(), "AES"), IvParameterSpec(iv.toByteArray()))
        return String(cipher.doFinal(Base64.getDecoder().decode(cipherText)))
    }

    companion object {
        /** 모의투자 TR ID. 실전은 H0STCNI0 (이 앱은 모의 연결만 쓴다). */
        const val DOMESTIC_TR_ID = "H0STCNI9"
        val TR_IDS = setOf(DOMESTIC_TR_ID, "H0STCNI0")

        /** 저장·로그 전에 비우는 칸(0부터): 0 고객 ID · 1 계좌번호 · 17 계좌명(국내 모의 실측 위치). 시크릿 규칙(CLAUDE.md). */
        val IDENTIFYING_FIELDS = setOf(0, 1, 17)
    }
}

package com.quantlog.broker.kis

import com.fasterxml.jackson.databind.JsonNode
import com.quantlog.broker.FillNotice
import mu.KotlinLogging
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

private val log = KotlinLogging.logger {}

/**
 * KIS 실시간 체결통보(국내 H0STCNI9 / 해외 H0GSCNI9, 모의) 수신 처리. 연결·구독은 [KisRealtimeClient] 가 같은 WebSocket 연결로 하고,
 * 여기는 (1) 구독 응답에 실려 오는 AES 키·IV 보관 (2) 수신 데이터 복호화 (3) 필드 파싱 → [FillNotice] 이벤트 발행만 한다.
 * 필드 순서는 공식 샘플(examples_llm ccnl_notice) 기준이고 모의에서 실측 전이다(docs/kis-api/field-reference.md).
 * 계좌번호·고객 ID·계좌명은 로그에 남기지 않는다.
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
        val overseas = trId in OVERSEAS_TR_IDS
        val fields = text.split("^")
        // 한 메시지에 여러 건이 이어 붙어 올 수 있다. 한 건의 필드 수는 사양 문서가 아니라 실제 건수로 나눠서 구한다
        // (2026-10-07 모의 실측: 문서는 26개인데 H0STCNI9 가 23개로 옴).
        if (recordCount <= 0 || fields.size % recordCount != 0) {
            return log.warn { "[체결통보] $trId 필드 수 ${fields.size} 를 건수 $recordCount 로 나눌 수 없어 버린다" }
        }
        val width = fields.size / recordCount
        val minWidth = if (overseas) OVERSEAS_MIN_FIELD_COUNT else DOMESTIC_MIN_FIELD_COUNT
        if (width < minWidth) return log.warn { "[체결통보] $trId 한 건이 ${width}필드뿐이라 파싱할 수 없다 (최소 $minWidth)" }
        fields.chunked(width).forEach { row ->
            logLayout(trId, row)
            val notice = if (overseas) parseOverseas(row) else parseDomestic(row)
            log.info {
                "[체결통보] ${if (overseas) "해외" else "국내"} 종목=${notice.symbol} 주문번호=${notice.orderNo} 원주문=${notice.originalOrderNo} " +
                    "구분=${notice.sellBuyCode} 체결여부=${notice.filledFlag} 접수=${notice.acceptFlag} 거부=${notice.refuseFlag} " +
                    "체결수량=${notice.filledQuantity} 체결단가=${notice.filledPrice} 주문수량=${notice.orderQuantity} 시각=${notice.time}"
            }
            eventPublisher.publishEvent(notice)
        }
    }

    /**
     * 임시 진단: 모의 필드 레이아웃을 실제 값으로 확인하려고 한 건의 필드를 위치와 함께 남긴다. 고객 ID(0)·계좌번호(1)·계좌명(17)은 가린다.
     * 레이아웃을 확정하고 field-reference.md 에 적은 뒤 지운다 (docs/todo.md).
     */
    private fun logLayout(
        trId: String,
        row: List<String>,
    ) {
        val masked = row.mapIndexed { i, v -> if (i in MASKED_INDEXES) "***" else v }
        log.info { "[체결통보 진단] $trId 필드 ${row.size}개: ${masked.withIndex().joinToString(" ") { "${it.index}=${it.value}" }}" }
    }

    internal fun parseDomestic(f: List<String>) =
        FillNotice(
            overseas = false,
            symbol = f[8],
            orderNo = f[2],
            originalOrderNo = f[3],
            sellBuyCode = f[4],
            filledFlag = f[13],
            acceptFlag = f[14],
            refuseFlag = f[12],
            filledQuantity = f[9].toBigDecimalOrNull(),
            filledPrice = f[10].toBigDecimalOrNull(),
            orderQuantity = f[16].toBigDecimalOrNull(),
            time = f[11],
        )

    internal fun parseOverseas(f: List<String>) =
        FillNotice(
            overseas = true,
            symbol = f[7],
            orderNo = f[2],
            originalOrderNo = f[3],
            sellBuyCode = f[4],
            filledFlag = f[12],
            acceptFlag = f[13],
            refuseFlag = f[11],
            filledQuantity = f[8].toBigDecimalOrNull(),
            filledPrice = f[9].toBigDecimalOrNull(),
            orderQuantity = f[15].toBigDecimalOrNull(),
            time = f[10],
        )

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
        /** 모의투자 TR ID. 실전은 H0STCNI0 / H0GSCNI0 (이 앱은 모의 연결만 쓴다). */
        const val DOMESTIC_TR_ID = "H0STCNI9"
        const val OVERSEAS_TR_ID = "H0GSCNI9"
        val OVERSEAS_TR_IDS = setOf(OVERSEAS_TR_ID, "H0GSCNI0")
        val TR_IDS = setOf(DOMESTIC_TR_ID, OVERSEAS_TR_ID, "H0STCNI0", "H0GSCNI0")
        private const val DOMESTIC_MIN_FIELD_COUNT = 17
        private const val OVERSEAS_MIN_FIELD_COUNT = 16
        private val MASKED_INDEXES = setOf(0, 1, 17)
    }
}

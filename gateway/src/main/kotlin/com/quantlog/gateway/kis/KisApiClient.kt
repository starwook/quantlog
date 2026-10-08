package com.quantlog.gateway.kis

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.quantlog.gateway.broker.CallPriority
import mu.KotlinLogging
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.util.concurrent.atomic.AtomicInteger

private val log = KotlinLogging.logger {}

/** KIS REST 호출 공통 처리: 인증 헤더, TR ID, 호출 간격 제한, 한도 초과 재시도. 응답 내용은 해석하지 않는다([raw]). */
@Component
class KisApiClient(
    private val properties: KisProperties,
    private val tokenProvider: KisTokenProvider,
    private val objectMapper: ObjectMapper,
    restClientBuilder: RestClient.Builder,
) {
    private val restClient = restClientBuilder.clone().baseUrl(properties.baseUrl).build()
    private var lastCallAt = 0L
    private val urgentWaiting = AtomicInteger(0)

    /** 응답을 해석하지 않고 돌려준다. [status] 는 한투가 준 HTTP 상태, [body] 는 응답 본문 원문, [trCont] 는 연속조회 응답 헤더(`tr_cont`)다. */
    data class RawResponse(
        val status: Int,
        val body: String,
        val trCont: String?,
    )

    /** 한투가 `rt_cd` 가 0 이 아닌 오류로 답했다(앱이 아닌 게이트웨이 안에서 쓰는 호출용). */
    fun get(
        path: String,
        trId: String,
        params: Map<String, String>,
    ): JsonNode = checked(path, trId, raw(path, trId, GET, params, null, null))

    fun post(
        path: String,
        trId: String,
        body: Map<String, String>,
    ): JsonNode = checked(path, trId, raw(path, trId, POST, emptyMap(), objectMapper.writeValueAsString(body), null))

    private fun checked(
        path: String,
        trId: String,
        response: RawResponse,
    ): JsonNode {
        val node = runCatching { objectMapper.readTree(response.body) }.getOrNull()
        if (node == null || node.isMissingNode || node.isNull) {
            throw KisApiException("KIS 응답을 읽지 못했습니다: $path ($trId) HTTP ${response.status} ${response.body.take(200)}")
        }
        if (node.path("rt_cd").asText() != "0") {
            val code = node.path("msg_cd").asText()
            throw KisApiException("KIS 오류 $path ($trId): [$code] ${node.path("msg1").asText()}", code = code)
        }
        return node
    }

    /**
     * 한투 REST 한 번 호출. 토큰·호출 간격·우선순위([CallPriority])는 여기서 처리하지만 요청·응답 내용은 건드리지 않는다.
     * [jsonBody] 는 POST 본문(미리 JSON 문자열로 직렬화해 정확한 바이트 길이를 넘긴다 — Map 을 그대로 넘기면 Content-Length 가 어긋나
     * KIS 게이트웨이가 EGW00202 로 거부한 사례가 있었다. 참고: https://wildeveloperetrain.tistory.com/426).
     * 초당 한도 초과(EGW00201/EGW00215)는 여러 스케줄러가 겹칠 때 실제로 발생한다(2026-09-29 실측: 청산 확인이 한도 초과로 통째로 실패해
     * 다음 30초까지 기다린 사례). 한도 초과일 때만 짧게 쉬었다가 한 번 더 시도하고, 그래도 오류면 그 응답을 그대로 돌려준다.
     */
    fun raw(
        path: String,
        trId: String,
        method: String,
        query: Map<String, String>,
        jsonBody: String?,
        trCont: String?,
    ): RawResponse {
        repeat(MAX_ATTEMPTS) { attempt ->
            val waitStart = System.currentTimeMillis()
            throttle()
            val httpStart = System.currentTimeMillis()
            val spec: RestClient.RequestHeadersSpec<*> =
                if (method == POST) {
                    restClient.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(jsonBody.orEmpty())
                } else {
                    restClient.get().uri { uri ->
                        uri.path(path)
                        query.forEach { (k, v) -> uri.queryParam(k, v) }
                        uri.build()
                    }
                }
            val response =
                spec
                    .header("authorization", "Bearer ${tokenProvider.accessToken()}")
                    .header("appkey", properties.appKey)
                    .header("appsecret", properties.appSecret)
                    .header("tr_id", trId)
                    .header("custtype", "P")
                    .also { if (!trCont.isNullOrBlank()) it.header("tr_cont", trCont) }
                    .exchange { _, res ->
                        RawResponse(
                            res.statusCode.value(),
                            String(res.body.readAllBytes(), Charsets.UTF_8),
                            res.headers.getFirst("tr_cont"),
                        )
                    }!!
            if (attempt < MAX_ATTEMPTS - 1 && isRateLimited(response.body)) {
                log.warn { "[KIS 한도 초과] $path ($trId) — ${RATE_LIMIT_RETRY_DELAY_MILLIS}ms 후 재시도" }
                Thread.sleep(RATE_LIMIT_RETRY_DELAY_MILLIS)
                return@repeat
            }
            logIfSlow(path, trId, waitMillis = httpStart - waitStart, httpMillis = System.currentTimeMillis() - httpStart)
            if (properties.logRaw) log.info { "KIS raw $path ($trId): ${response.body}" }
            return response
        }
        throw KisApiException("KIS 호출 실패(재시도 초과) $path ($trId)")
    }

    /** 느린 원인을 가르려고 남긴다: 공용 호출 간격 대기(throttle)가 길면 다른 호출에 밀린 것이고, 응답이 길면 KIS 서버가 느린 것이다. */
    private fun logIfSlow(
        path: String,
        trId: String,
        waitMillis: Long,
        httpMillis: Long,
    ) {
        if (waitMillis > SLOW_WAIT_MILLIS || httpMillis > SLOW_HTTP_MILLIS) {
            log.warn { "[KIS 느린 호출] $path ($trId) 대기 ${waitMillis}ms + 응답 ${httpMillis}ms" }
        }
    }

    private fun isRateLimited(body: String): Boolean = body.contains("EGW00201") || body.contains("EGW00215")

    /**
     * 호출 간격을 지킨다. 즉발 호출([CallPriority])이 기다리는 동안엔 일반 호출(스케줄러)이 양보한다 — 우선순위 매뉴얼은 [CallPriority] 참고.
     * 잠은 잠금 밖에서 잔다(잠금 안에서 자면 즉발 호출도 같이 막힌다).
     */
    private fun throttle() {
        val urgent = CallPriority.isUrgent()
        if (urgent) urgentWaiting.incrementAndGet()
        try {
            while (true) {
                val sleepMillis =
                    synchronized(this) {
                        val now = System.currentTimeMillis()
                        val wait = lastCallAt + properties.minIntervalMillis - now
                        when {
                            !urgent && urgentWaiting.get() > 0 -> YIELD_MILLIS
                            wait > 0 -> wait
                            else -> {
                                lastCallAt = now
                                return
                            }
                        }
                    }
                Thread.sleep(sleepMillis)
            }
        } finally {
            if (urgent) urgentWaiting.decrementAndGet()
        }
    }

    companion object {
        const val GET = "GET"
        const val POST = "POST"
        private const val MAX_ATTEMPTS = 2
        private const val RATE_LIMIT_RETRY_DELAY_MILLIS = 1500L
        private const val YIELD_MILLIS = 50L
        private const val SLOW_WAIT_MILLIS = 2000L
        private const val SLOW_HTTP_MILLIS = 1500L
    }
}

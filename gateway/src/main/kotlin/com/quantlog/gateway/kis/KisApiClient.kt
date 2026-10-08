package com.quantlog.gateway.kis

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.quantlog.gateway.broker.CallPriority
import mu.KotlinLogging
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import java.util.concurrent.atomic.AtomicInteger

private val log = KotlinLogging.logger {}

/** KIS REST 호출 공통 처리: 인증 헤더, TR ID, 호출 간격 제한, rt_cd 에러 변환. */
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

    fun get(
        path: String,
        trId: String,
        params: Map<String, String>,
    ): JsonNode =
        execute(path, trId) { spec ->
            spec.get()
                .uri { uri ->
                    uri.path(path)
                    params.forEach { (k, v) -> uri.queryParam(k, v) }
                    uri.build()
                }
        }

    fun post(
        path: String,
        trId: String,
        body: Map<String, String>,
    ): JsonNode =
        execute(path, trId) { spec ->
            // Map 객체를 그대로 넘기면 Spring이 쓰기 시점에 직렬화하며 Content-Length가 어긋나
            // KIS 게이트웨이가 EGW00202(GW라우팅 오류)로 거부하는 사례가 있었다. 미리 JSON 문자열로
            // 직렬화해 정확한 바이트 길이를 넘긴다. (참고: https://wildeveloperetrain.tistory.com/426)
            val json = objectMapper.writeValueAsString(body)
            spec.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(json)
        }

    /**
     * 초당 한도 초과(EGW00201/EGW00215)는 여러 스케줄러가 겹칠 때 실제로 발생한다(2026-09-29 실측:
     * 청산 스케줄러가 "1% 익절 됐는데 안 팔렸다"고 느껴진 사례의 원인 — 그 순간 확인이 한도 초과로 통째로
     * 실패하고 다음 30초까지 기다린 것). 한도 초과일 때만 짧게 쉬었다가 한 번 더 시도한다.
     */
    private fun execute(
        path: String,
        trId: String,
        build: (RestClient) -> RestClient.RequestHeadersSpec<*>,
    ): JsonNode {
        repeat(MAX_ATTEMPTS) { attempt ->
            val waitStart = System.currentTimeMillis()
            throttle()
            val httpStart = System.currentTimeMillis()
            try {
                val response =
                    build(restClient)
                        .header("authorization", "Bearer ${tokenProvider.accessToken()}")
                        .header("appkey", properties.appKey)
                        .header("appsecret", properties.appSecret)
                        .header("tr_id", trId)
                        .header("custtype", "P")
                        .retrieve()
                        .body(JsonNode::class.java)
                        ?: throw KisApiException("KIS 응답이 비어 있습니다: $path ($trId)")

                if (response.path("rt_cd").asText() != "0") {
                    val code = response.path("msg_cd").asText()
                    throw KisApiException("KIS 오류 $path ($trId): [$code] ${response.path("msg1").asText()}", code = code)
                }
                logIfSlow(path, trId, waitMillis = httpStart - waitStart, httpMillis = System.currentTimeMillis() - httpStart)
                if (properties.logRaw) log.info { "KIS raw $path ($trId): $response" }
                return response
            } catch (e: RestClientResponseException) {
                val body = e.responseBodyAsString
                if (attempt < MAX_ATTEMPTS - 1 && isRateLimited(body)) {
                    log.warn { "[KIS 한도 초과] $path ($trId) — ${RATE_LIMIT_RETRY_DELAY_MILLIS}ms 후 재시도" }
                    Thread.sleep(RATE_LIMIT_RETRY_DELAY_MILLIS)
                    return@repeat
                }
                throw KisApiException("KIS 호출 실패 $path ($trId): HTTP ${e.statusCode.value()} $body", e)
            }
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

    private companion object {
        const val MAX_ATTEMPTS = 2
        const val RATE_LIMIT_RETRY_DELAY_MILLIS = 1500L
        const val YIELD_MILLIS = 50L
        const val SLOW_WAIT_MILLIS = 2000L
        const val SLOW_HTTP_MILLIS = 1500L
    }
}

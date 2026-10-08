package com.quantlog.gatewayclient

import com.fasterxml.jackson.databind.ObjectMapper
import com.quantlog.broker.CallPriority
import com.quantlog.broker.KisReply
import com.quantlog.broker.KisRest
import mu.KotlinLogging
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import java.net.http.HttpClient
import java.time.Duration

private val log = KotlinLogging.logger {}

@ConfigurationProperties(prefix = "quantlog.gateway")
data class GatewayClientProperties(
    val baseUrl: String = "http://localhost:8081",
    /** 게이트웨이와 맞춘 공유 토큰(`X-Gateway-Token`). 비우면 보내지 않는다. */
    val token: String = "",
    val connectTimeoutMillis: Long = 2_000,
    /** 증권사 호출은 호출 간격 대기열 때문에 몇 초 걸릴 수 있다(주문은 더). */
    val readTimeoutMillis: Long = 30_000,
    /** false 면 게이트웨이와 연결하지 않는다(테스트·점검용). */
    val enabled: Boolean = true,
)

/** 게이트웨이가 증권사나 요청 때문에 실패를 돌려줬다. [status] 는 HTTP 상태, [code] 는 증권사 msg_cd 또는 게이트웨이 코드. */
class GatewayException(
    message: String,
    val status: Int,
    val code: String?,
) : RuntimeException(message)

/** 게이트웨이에 닿지 못했다(연결 거부·시간 초과). 주문은 보내졌는지 모를 수 있다 — 호출한 쪽이 주문 상태를 조회해 확정해야 한다. */
class GatewayUnavailableException(message: String, cause: Throwable?) : RuntimeException(message, cause)

/**
 * 한투 REST 를 게이트웨이(HTTP `/api/kis/<한투 경로>`)로 보내는 [KisRest]. 앱은 한투에 직접 닿지 않는다 — 키도 없다. 형식은 docs/contracts/README.md.
 * 요청은 한투 문서 그대로(TR ID 는 헤더 `tr_id`)이고 응답은 한투 JSON 원문이다. 지금 스레드가 즉발 등급이면([CallPriority]) `X-Call-Priority: urgent` 헤더로 알려
 * 게이트웨이의 호출 대기열에서 먼저 나가게 한다. 주문·취소는 요청 ID(`X-Request-Id`)를 붙인다 — 게이트웨이가 같은 ID 의 중복 요청을 막는다.
 */
@Component
class GatewayClient(
    private val properties: GatewayClientProperties,
    private val objectMapper: ObjectMapper,
) : KisRest {
    private val client: RestClient =
        RestClient.builder()
            .baseUrl(properties.baseUrl)
            .requestFactory(
                JdkClientHttpRequestFactory(
                    HttpClient.newBuilder().connectTimeout(Duration.ofMillis(properties.connectTimeoutMillis)).build(),
                ).also { it.setReadTimeout(Duration.ofMillis(properties.readTimeoutMillis)) },
            )
            .build()

    override fun get(
        path: String,
        trId: String,
        params: Map<String, String>,
        trCont: String,
    ): KisReply =
        send(
            client.get().uri { uri ->
                uri.path(KIS_PREFIX + path)
                params.forEach { (k, v) -> uri.queryParam(k, v) }
                uri.build()
            },
            trId,
            trCont,
            requestId = null,
        )

    override fun post(
        path: String,
        trId: String,
        body: Map<String, String>,
        requestId: String?,
    ): KisReply = send(client.post().uri(KIS_PREFIX + path).body(body), trId, trCont = "", requestId)

    /** 감시 종목이 바뀌었다고 알려 게이트웨이가 실시간 구독을 지금 맞추게 한다. 한투 호출이 아니라 게이트웨이 전용 신호다. */
    fun refreshWatchSymbols() {
        call {
            client.post().uri(
                "/api/watch/refresh",
            ).headers(::addHeaders).body(emptyMap<String, String>()).retrieve().toBodilessEntity()
        }
    }

    private fun send(
        spec: RestClient.RequestHeadersSpec<*>,
        trId: String,
        trCont: String,
        requestId: String?,
    ): KisReply {
        val raw =
            call {
                spec.headers {
                    addHeaders(it)
                    it.set("tr_id", trId)
                    if (trCont.isNotBlank()) it.set("tr_cont", trCont)
                    if (requestId != null) it.set(REQUEST_ID_HEADER, requestId)
                }.exchange { _, res ->
                    Raw(res.statusCode.value(), String(res.body.readAllBytes(), Charsets.UTF_8), res.headers.getFirst("tr_cont").orEmpty())
                }!!
            }
        val node = runCatching { objectMapper.readTree(raw.body) }.getOrNull()
        // 한투 응답(오류 포함)에는 rt_cd 가 있다. 없으면 한투가 아니라 게이트웨이가 낸 오류({error, code})다.
        if (node != null && node.has("rt_cd")) return KisReply(node, raw.trCont)
        val message = node?.path("error")?.asText()?.takeIf { it.isNotBlank() } ?: "게이트웨이 오류 HTTP ${raw.status}"
        val code = node?.path("code")?.asText()?.takeIf { it.isNotBlank() }
        throw GatewayException(message, raw.status, code)
    }

    private fun addHeaders(headers: org.springframework.http.HttpHeaders) {
        if (properties.token.isNotBlank()) headers.set(TOKEN_HEADER, properties.token)
        if (CallPriority.isUrgent()) headers.set(PRIORITY_HEADER, "urgent")
    }

    private fun <T> call(block: () -> T): T =
        try {
            block()
        } catch (e: RestClientResponseException) {
            val body = runCatching { objectMapper.readTree(e.responseBodyAsString) }.getOrNull()
            val message = body?.path("error")?.asText()?.takeIf { it.isNotBlank() } ?: "게이트웨이 오류 HTTP ${e.statusCode.value()}"
            val code = body?.path("code")?.asText()?.takeIf { it.isNotBlank() }
            throw GatewayException(message, e.statusCode.value(), code)
        } catch (e: ResourceAccessException) {
            log.debug { "게이트웨이에 닿지 못했다: ${e.message}" }
            throw GatewayUnavailableException("게이트웨이에 닿지 못했다: ${e.message}", e)
        }

    private class Raw(val status: Int, val body: String, val trCont: String)

    private companion object {
        const val KIS_PREFIX = "/api/kis"
        const val TOKEN_HEADER = "X-Gateway-Token"
        const val PRIORITY_HEADER = "X-Call-Priority"
        const val REQUEST_ID_HEADER = "X-Request-Id"
    }
}

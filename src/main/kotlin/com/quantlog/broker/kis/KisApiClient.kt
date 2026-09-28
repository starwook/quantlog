package com.quantlog.broker.kis

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import mu.KotlinLogging
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException

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

    private fun execute(
        path: String,
        trId: String,
        build: (RestClient) -> RestClient.RequestHeadersSpec<*>,
    ): JsonNode {
        throttle()
        val response: JsonNode =
            try {
                build(restClient)
                    .header("authorization", "Bearer ${tokenProvider.accessToken()}")
                    .header("appkey", properties.appKey)
                    .header("appsecret", properties.appSecret)
                    .header("tr_id", trId)
                    .header("custtype", "P")
                    .retrieve()
                    .body(JsonNode::class.java)
            } catch (e: RestClientResponseException) {
                throw KisApiException("KIS 호출 실패 $path ($trId): HTTP ${e.statusCode.value()} ${e.responseBodyAsString}", e)
            } ?: throw KisApiException("KIS 응답이 비어 있습니다: $path ($trId)")

        if (response.path("rt_cd").asText() != "0") {
            throw KisApiException(
                "KIS 오류 $path ($trId): [${response.path("msg_cd").asText()}] ${response.path("msg1").asText()}",
            )
        }
        if (properties.logRaw) log.info { "KIS raw $path ($trId): $response" }
        return response
    }

    @Synchronized
    private fun throttle() {
        val wait = lastCallAt + properties.minIntervalMillis - System.currentTimeMillis()
        if (wait > 0) Thread.sleep(wait)
        lastCallAt = System.currentTimeMillis()
    }
}

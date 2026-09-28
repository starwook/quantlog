package com.quantlog.broker.kis

import com.fasterxml.jackson.databind.JsonNode
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
            spec.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body)
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

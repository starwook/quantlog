package com.quantlog.gateway.kis

import com.fasterxml.jackson.databind.JsonNode
import mu.KotlinLogging
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import java.time.Duration
import java.time.Instant

private val log = KotlinLogging.logger {}

/** 접근토큰(24시간 유효)을 메모리에 캐시한다. 발급 API는 호출 빈도 제한이 있어 만료 직전에만 재발급한다. */
@Component
class KisTokenProvider(
    private val properties: KisProperties,
    restClientBuilder: RestClient.Builder,
) {
    private val restClient = restClientBuilder.clone().baseUrl(properties.baseUrl).build()
    private var token: String? = null
    private var expiresAt: Instant = Instant.EPOCH
    private var wsApprovalKey: String? = null
    private var wsApprovalIssuedAt: Instant = Instant.EPOCH

    @Synchronized
    fun accessToken(): String {
        val cached = token
        if (cached != null && Instant.now().isBefore(expiresAt.minus(EXPIRY_MARGIN))) return cached
        properties.requireCredentials()
        val issued = issue()
        token = issued.first
        expiresAt = issued.second
        log.info { "KIS access token issued (expires at $expiresAt)" }
        return issued.first
    }

    /** 실시간 WebSocket 접속키. REST 토큰과 발급 엔드포인트·유효기간(24시간)이 별개다. */
    @Synchronized
    fun approvalKey(): String {
        val cached = wsApprovalKey
        if (cached != null && Instant.now().isBefore(wsApprovalIssuedAt.plus(APPROVAL_TTL).minus(EXPIRY_MARGIN))) return cached
        properties.requireCredentials()
        val body =
            mapOf(
                "grant_type" to "client_credentials",
                "appkey" to properties.appKey,
                "secretkey" to properties.appSecret,
            )
        val response: JsonNode =
            try {
                restClient.post()
                    .uri("/oauth2/Approval")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode::class.java)
            } catch (e: RestClientResponseException) {
                throw KisApiException("KIS 실시간 접속키 발급 실패: HTTP ${e.statusCode.value()} ${e.responseBodyAsString}", e)
            } ?: throw KisApiException("KIS 실시간 접속키 발급 응답이 비어 있습니다.")

        val key = response.path("approval_key").asText("")
        if (key.isBlank()) throw KisApiException("KIS 실시간 접속키 발급 실패: $response")
        wsApprovalKey = key
        wsApprovalIssuedAt = Instant.now()
        log.info { "KIS realtime approval key issued" }
        return key
    }

    private fun issue(): Pair<String, Instant> {
        val body =
            mapOf(
                "grant_type" to "client_credentials",
                "appkey" to properties.appKey,
                "appsecret" to properties.appSecret,
            )
        val response: JsonNode =
            try {
                restClient.post()
                    .uri("/oauth2/tokenP")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode::class.java)
            } catch (e: RestClientResponseException) {
                throw KisApiException("KIS 토큰 발급 실패: HTTP ${e.statusCode.value()} ${e.responseBodyAsString}", e)
            } ?: throw KisApiException("KIS 토큰 발급 응답이 비어 있습니다.")

        val accessToken = response.path("access_token").asText("")
        if (accessToken.isBlank()) {
            throw KisApiException("KIS 토큰 발급 실패: ${response.path("error_description").asText(response.toString())}")
        }
        val expiresInSeconds = response.path("expires_in").asLong(DEFAULT_TTL.seconds)
        return accessToken to Instant.now().plusSeconds(expiresInSeconds)
    }

    private companion object {
        val EXPIRY_MARGIN: Duration = Duration.ofMinutes(5)
        val DEFAULT_TTL: Duration = Duration.ofHours(23)
        val APPROVAL_TTL: Duration = Duration.ofHours(24)
    }
}

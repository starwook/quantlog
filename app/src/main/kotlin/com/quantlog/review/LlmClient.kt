package com.quantlog.review

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/** Anthropic API 접속 정보. 리뷰뿐 아니라 이후 진입 판단도 같은 키를 쓴다. */
@ConfigurationProperties(prefix = "quantlog.anthropic")
data class AnthropicProperties(
    /** API 키 (시크릿 — application-local.yml 이나 환경변수에만 적는다). */
    val apiKey: String = "",
    /** 기본 모델(2026-10-13 사용자 결정: Haiku 5.5). 모델을 바꾸려면 이 값만 고친다. */
    val model: String = "claude-haiku-5-5",
)

@ConfigurationProperties(prefix = "quantlog.review")
data class ReviewProperties(
    /** 스케줄러(장 마감 뒤 자동 리뷰) 켜기. 호출마다 API 비용이 나가므로 기본은 꺼 둔다. 수동 실행(/review/run)은 이 값과 무관하다. */
    val enabled: Boolean = false,
    /** 일별 요약에 넣을 최근 일수. */
    val days: Int = 7,
)

/** 리뷰가 쓰는 LLM 호출 한 번. 테스트에서 가짜로 바꿀 수 있게 인터페이스 뒤에 둔다. */
interface LlmClient {
    fun complete(
        system: String,
        user: String,
    ): String
}

@Component
class AnthropicLlmClient(
    private val anthropic: AnthropicProperties,
    private val properties: ReviewProperties,
) : LlmClient {
    // 키가 없는 환경(기본)에서도 앱이 떠야 해서 첫 호출 때 만든다.
    private val client: AnthropicClient by lazy {
        check(anthropic.apiKey.isNotBlank()) { "quantlog.anthropic.api-key 가 비어 있습니다 (application-local.yml 에 적으세요)" }
        AnthropicOkHttpClient.builder().apiKey(anthropic.apiKey).build()
    }

    override fun complete(
        system: String,
        user: String,
    ): String {
        val params =
            MessageCreateParams.builder()
                .model(anthropic.model)
                .maxTokens(16_000L)
                .system(system)
                .addUserMessage(user)
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.MEDIUM).build())
                .build()
        val message = client.messages().create(params)
        check(message.stopReason().map { it.asString() }.orElse("") != "refusal") { "Claude 가 요청을 거절했습니다" }
        return message.content().flatMap { block -> block.text().map { it.text() }.stream().toList() }.joinToString("")
    }
}

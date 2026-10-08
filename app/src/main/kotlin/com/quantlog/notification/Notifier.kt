package com.quantlog.notification

import mu.KotlinLogging
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.util.concurrent.Executors

private val log = KotlinLogging.logger {}

@ConfigurationProperties(prefix = "quantlog.notify")
data class NotifyProperties(
    /** Discord 또는 Slack Incoming Webhook URL. 비어 있으면 알림을 보내지 않는다 (시크릿이라 application-local.yml 에만). */
    val webhookUrl: String = "",
)

/**
 * 웹훅 알림 발송. Discord 는 {"content"}, Slack 은 {"text"} 를 받으므로 URL 로 구분한다.
 * 전송은 별도 스레드에서 하고 실패해도 매매 흐름엔 영향이 없다. 실패 로그는 debug 로만 남긴다 —
 * 에러 로그 → 알림 → 실패 로그 → 알림 …으로 무한 반복되지 않게.
 */
@Component
class Notifier(private val properties: NotifyProperties) {
    private val client = RestClient.create()
    private val executor =
        Executors.newSingleThreadExecutor { r -> Thread(r, "notifier").apply { isDaemon = true } }

    val enabled: Boolean get() = properties.webhookUrl.isNotBlank()

    fun send(message: String) {
        if (!enabled) return
        executor.execute {
            runCatching {
                client.post()
                    .uri(properties.webhookUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload(message))
                    .retrieve()
                    .toBodilessEntity()
            }.onFailure { log.debug(it) { "[알림] 웹훅 전송 실패" } }
        }
    }

    fun payload(message: String): Map<String, String> {
        val text = message.take(MAX_LENGTH)
        val key = if (properties.webhookUrl.contains("discord")) "content" else "text"
        return mapOf(key to text)
    }

    private companion object {
        const val MAX_LENGTH = 1900 // Discord 한도 2000자
    }
}

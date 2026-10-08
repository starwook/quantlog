package com.quantlog.gatewayclient

import com.quantlog.errorlog.ErrorLogRepository
import com.quantlog.notification.Notifier
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * 게이트웨이가 `error_log` 에 쌓은 WARN 이상 로그를 웹훅으로 전달한다. 웹훅은 앱이 맡으므로(게이트웨이는 알림을 보내지 않는다) 같은 DB 의
 * `error_log` 중 게이트웨이 로거(`com.quantlog.gateway`) 행을 주기적으로 읽는다. 같은 오류는 1분에 한 번만.
 *
 * 동기화 불일치 보고 불필요: 증권사와 비교하지 않는다. 게이트웨이가 남긴 오류 기록을 알림으로 넘기는 일이다.
 */
@Component
class GatewayErrorForwarder(
    private val errors: ErrorLogRepository,
    private val notifier: Notifier,
) {
    private var cursor: Instant = Instant.now()
    private val lastSentAt = ConcurrentHashMap<String, Long>()

    @Scheduled(fixedDelay = 5_000, initialDelay = 5_000)
    fun run() {
        if (!notifier.enabled) return
        val since = cursor
        val rows =
            runCatching { errors.findAllByLastSeenAfterAndLoggerStartingWithOrderByLastSeenAsc(since, GATEWAY_LOGGER) }.getOrNull()
                ?: return
        rows.forEach { row ->
            cursor = maxOf(cursor, row.lastSeen)
            val now = System.currentTimeMillis()
            val previous = lastSentAt.put(row.fingerprint, now)
            if (previous != null && now - previous < DEDUPE_MILLIS) return@forEach
            val icon = if (row.level == "ERROR") "🚨" else "⚠️"
            val cause = row.exceptionClass?.let { "\n$it: ${row.exceptionMessage.orEmpty()}" } ?: ""
            notifier.send("$icon [quantlog gateway ${row.level}] ${row.message}$cause")
        }
    }

    private companion object {
        const val GATEWAY_LOGGER = "com.quantlog.gateway"
        const val DEDUPE_MILLIS = 60_000L
    }
}

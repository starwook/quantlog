package com.quantlog.notification

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/** WARN 이상 로그(체결가 조회 실패, 감시 실패 등)를 웹훅으로 보낸다. 같은 메시지는 1분에 한 번만. */
@Component
class ErrorLogNotifier(private val notifier: Notifier) {
    private val appender =
        object : AppenderBase<ILoggingEvent>() {
            private val lastSentAt = ConcurrentHashMap<String, Long>()

            override fun append(event: ILoggingEvent) {
                if (event.level.isGreaterOrEqual(Level.WARN).not()) return
                if (event.loggerName.startsWith(NOTIFY_PACKAGE)) return
                val now = System.currentTimeMillis()
                val previous = lastSentAt.put(event.formattedMessage, now)
                if (previous != null && now - previous < DEDUPE_MILLIS) return
                notifier.send(format(event))
            }
        }

    @PostConstruct
    fun attach() {
        if (!notifier.enabled) return
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        appender.context = root.loggerContext
        appender.start()
        root.addAppender(appender)
    }

    @PreDestroy
    fun detach() {
        (LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger).detachAppender(appender)
        appender.stop()
    }

    private fun format(event: ILoggingEvent): String {
        val icon = if (event.level.isGreaterOrEqual(Level.ERROR)) "🚨" else "⚠️"
        val cause = event.throwableProxy?.let { "\n${it.className}: ${it.message}" } ?: ""
        return "$icon [quantlog ${event.level}] ${event.formattedMessage}$cause"
    }

    private companion object {
        const val NOTIFY_PACKAGE = "com.quantlog.notification"
        const val DEDUPE_MILLIS = 60_000L
    }
}

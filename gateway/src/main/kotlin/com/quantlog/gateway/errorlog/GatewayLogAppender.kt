package com.quantlog.gateway.errorlog

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.AppenderBase
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import mu.KotlinLogging
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

private val log = KotlinLogging.logger {}

/**
 * WARN 이상 로그를 DB(`gateway_log`)에 원본 그대로 한 줄씩 쌓는다. 게이트웨이는 알림을 보내지 않으므로 앱이 이 줄을 읽어 간다.
 * 매매·시세 흐름을 막지 않도록 전용 스레드 하나가 큐(최대 [QUEUE_SIZE]건, 넘치면 버림)에서 꺼내 쓴다.
 * DB 쓰기 자체가 내는 로그(Hibernate·Hikari 등)와 이 패키지의 로그는 기록 대상이 아니다 — 쓰기 실패 → 로그 → 쓰기 …로 무한 반복되지 않게.
 */
@Component
class GatewayLogAppender(
    private val repository: GatewayLogRepository,
    private val properties: ErrorLogProperties,
) {
    private val executor =
        ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(QUEUE_SIZE),
            { r -> Thread(r, "gateway-log").apply { isDaemon = true } },
            ThreadPoolExecutor.DiscardPolicy(),
        )

    private val appender =
        object : AppenderBase<ILoggingEvent>() {
            override fun append(event: ILoggingEvent) {
                if (!event.level.isGreaterOrEqual(Level.WARN)) return
                if (IGNORED_LOGGERS.any { event.loggerName.startsWith(it) }) return
                val row = event.toRow()
                executor.execute {
                    runCatching { repository.save(row) }
                        .onFailure { log.debug(it) { "[게이트웨이 로그] 저장 실패" } }
                }
            }
        }

    @PostConstruct
    fun attach() {
        if (!properties.enabled) return
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        appender.context = root.loggerContext
        appender.start()
        root.addAppender(appender)
    }

    @PreDestroy
    fun detach() {
        (LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger).detachAppender(appender)
        appender.stop()
        executor.shutdown()
    }

    private fun ILoggingEvent.toRow() =
        GatewayLog(
            loggedAt = Instant.ofEpochMilli(timeStamp),
            level = level.toString(),
            logger = loggerName.take(200),
            message = formattedMessage.take(MAX_MESSAGE),
            exceptionClass = throwableProxy?.className?.take(200),
            exceptionMessage = throwableProxy?.message?.take(500),
            stackTrace = throwableProxy?.let { ThrowableProxyUtil.asString(it) }?.take(MAX_STACK),
        )

    private companion object {
        const val QUEUE_SIZE = 1000
        const val MAX_MESSAGE = 1000
        const val MAX_STACK = 6000
        val IGNORED_LOGGERS =
            listOf(
                "com.quantlog.gateway.errorlog",
                "org.hibernate",
                "com.zaxxer",
                "com.mysql",
                "org.springframework.orm",
                "org.springframework.jdbc",
                "org.springframework.transaction",
            )
    }
}

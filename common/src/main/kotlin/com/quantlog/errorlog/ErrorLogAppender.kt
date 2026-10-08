package com.quantlog.errorlog

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
 * WARN 이상 로그를 DB(error_log)에 쌓는다. 서버 로그를 직접 못 볼 때 오류가 지나쳐지지 않게 하는 기록이다.
 * 매매 흐름을 막지 않도록 전용 스레드 하나가 큐(최대 [QUEUE_SIZE]건, 넘치면 버림)에서 꺼내 쓴다.
 * DB 쓰기 자체가 내는 로그(Hibernate·Hikari 등)와 이 패키지의 로그는 기록 대상이 아니다 — 쓰기 실패 → 로그 → 쓰기 …로 무한 반복되지 않게.
 */
@Component
class ErrorLogAppender(
    private val recorder: ErrorLogRecorder,
    private val properties: ErrorLogProperties,
) {
    private val executor =
        ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(QUEUE_SIZE),
            { r -> Thread(r, "error-log").apply { isDaemon = true } },
            ThreadPoolExecutor.DiscardPolicy(),
        )

    private val appender =
        object : AppenderBase<ILoggingEvent>() {
            override fun append(event: ILoggingEvent) {
                if (!event.level.isGreaterOrEqual(Level.WARN)) return
                if (IGNORED_LOGGERS.any { event.loggerName.startsWith(it) }) return
                val record = event.toRecord()
                executor.execute {
                    runCatching { recorder.record(record) }
                        .onFailure { log.debug(it) { "[오류 기록] 저장 실패" } }
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

    private fun ILoggingEvent.toRecord(): LogRecord =
        LogRecord(
            at = Instant.ofEpochMilli(timeStamp),
            level = level.toString(),
            logger = loggerName,
            message = formattedMessage,
            exceptionClass = throwableProxy?.className,
            exceptionMessage = throwableProxy?.message,
            stackTrace = throwableProxy?.let { ThrowableProxyUtil.asString(it) },
        )

    private companion object {
        const val QUEUE_SIZE = 1000
        val IGNORED_LOGGERS =
            listOf(
                "com.quantlog.errorlog",
                "org.hibernate",
                "com.zaxxer",
                "com.mysql",
                "org.springframework.orm",
                "org.springframework.jdbc",
                "org.springframework.transaction",
            )
    }
}

package com.quantlog.sync

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

/** 테스트 동안 `[동기화 불일치]` ERROR 로그를 모아 둔다. `use { }` 로 쓰면 끝날 때 떼어낸다. */
class MismatchLog : AutoCloseable {
    private val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
    private val appender = ListAppender<ILoggingEvent>().also { it.start() }

    init {
        root.addAppender(appender)
    }

    val messages: List<String>
        get() =
            appender.list.filter {
                it.level == Level.ERROR && it.formattedMessage.startsWith(SyncMismatchReporter.TAG)
            }.map { it.formattedMessage }

    override fun close() {
        root.detachAppender(appender)
    }
}

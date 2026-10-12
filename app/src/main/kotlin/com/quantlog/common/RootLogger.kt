package com.quantlog.common

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.Appender
import org.slf4j.LoggerFactory

/** 루트 로거에 어펜더를 붙이고 떼는 일. WARN 이상 로그를 따로 모으는 기능들이 같이 쓴다. */
object RootLogger {
    private val root: Logger get() = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger

    fun attach(appender: Appender<ILoggingEvent>) {
        appender.context = root.loggerContext
        appender.start()
        root.addAppender(appender)
    }

    fun detach(appender: Appender<ILoggingEvent>) {
        root.detachAppender(appender)
        appender.stop()
    }
}

package com.quantlog.errorlog

import mu.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

private val log = KotlinLogging.logger {}

/** 오래된 오류 기록을 매일 새벽에 지운다([ErrorLogProperties.retentionDays]). */
@Component
class ErrorLogCleaner(
    private val recorder: ErrorLogRecorder,
    private val properties: ErrorLogProperties,
) {
    @Scheduled(cron = "0 30 4 * * *", zone = "Asia/Seoul")
    fun purgeOld() {
        if (!properties.enabled) return
        val deleted = recorder.purge(Instant.now().minus(Duration.ofDays(properties.retentionDays)))
        if (deleted > 0) log.info { "[오류 기록] ${properties.retentionDays}일 지난 ${deleted}건 삭제" }
    }
}

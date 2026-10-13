package com.quantlog.trading

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.ZonedDateTime

/** 진입 스케줄러 주문(저점 판단 진입 제외)이 제한 시간 안에 체결 확인이 안 되면 취소하는 주기 점검. */
@Component
class EntryExpiryScheduler(
    private val entryScheduler: EntryScheduler,
    private val properties: EntryProperties,
) {
    @Scheduled(fixedDelay = 1000, initialDelay = 20_000)
    fun run() {
        if (properties.enabled) entryScheduler.expirePending(ZonedDateTime.now())
    }
}

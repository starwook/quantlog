package com.quantlog.trading

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.ZonedDateTime

/** 마틴게일 주문이 제한 시간 안에 체결 확인이 안 되면 취소하는 주기 점검. 판정 자체는 틱이 한다([MartingaleTickListener]). */
@Component
class MartingaleScheduler(
    private val martingaleService: MartingaleService,
    private val properties: EntryProperties,
) {
    @Scheduled(fixedDelay = 1000, initialDelay = 20_000)
    fun run() {
        if (properties.enabled) martingaleService.expirePending(ZonedDateTime.now())
    }
}

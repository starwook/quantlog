package com.quantlog.trading

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.ZonedDateTime

/**
 * 폴링 방식 청산 감시. 실시간 틱([ExitTickListener])과 별개로 **보유 종목 전부**를 주기마다 판정한다 — 틱이 앱까지 안 오는 동안
 * (게이트웨이 스트림 끊김 등, 구독 중이라 "실시간"으로 보이는 종목 포함)에도 손절이 멈추지 않게 하는 안전망이다(2026-11-02).
 * 틱과 폴링이 같은 종목을 동시에 판정하는 일은 [ExitService] 의 종목별 잠금이 막는다. 현재가는 실시간 값이 신선하면 그것을 써서 REST 호출이 없다.
 */
@Component
class ExitScheduler(
    private val exitService: ExitService,
    private val properties: ExitProperties,
) {
    @Scheduled(
        fixedDelayString = "\${quantlog.exit.interval-millis:30000}",
        initialDelayString = "\${quantlog.exit.initial-delay-millis:15000}",
    )
    fun run() {
        if (!properties.enabled) return
        val now = ZonedDateTime.now()
        exitService.expirePending(now)
        exitService.checkAll(now)
    }
}

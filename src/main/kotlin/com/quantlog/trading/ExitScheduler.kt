package com.quantlog.trading

import com.quantlog.broker.RealtimePriceFeed
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.ZonedDateTime

/**
 * 폴링 방식 청산 감시. 실시간 시세([RealtimePriceFeed])가 커버하지 않는 종목(해외, 실시간 꺼짐/연결 끊김 포함)만 맡는다.
 * 실시간이 커버하는 종목은 [ExitTickListener] 가 틱마다 처리한다.
 */
@Component
class ExitScheduler(
    private val exitService: ExitService,
    private val realtimeFeed: RealtimePriceFeed,
    private val properties: ExitProperties,
) {
    @Scheduled(
        fixedDelayString = "\${quantlog.exit.interval-millis:30000}",
        initialDelayString = "\${quantlog.exit.initial-delay-millis:15000}",
    )
    fun run() {
        if (properties.enabled) exitService.checkAll(ZonedDateTime.now(), realtimeFeed::isLive)
    }
}

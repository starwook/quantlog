package com.quantlog.trading

import com.quantlog.broker.BrokerClient
import com.quantlog.broker.Market
import com.quantlog.position.HoldingSyncService
import com.quantlog.position.TradeFilledEvent
import mu.KotlinLogging
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

private val log = KotlinLogging.logger {}

@ConfigurationProperties(prefix = "quantlog.holding-sync")
data class HoldingSyncProperties(
    val enabled: Boolean = true,
)

/**
 * KIS 잔고를 주기적으로 DB(account_holding)에 그대로 맞춘다. 증권사 앱에서 직접 거래한 물량도 잔고에 있으므로 따라온다.
 * 현재가도 같이 저장돼 화면은 KIS를 부르지 않는다. 화면을 열어야만 동기화되면 안 된다
 * (2026-09-30 사용자 지적: 화면을 켜두고 앱에서 사면 어긋남).
 */
@Component
class HoldingSyncScheduler(
    private val broker: BrokerClient,
    private val holdingSync: HoldingSyncService,
    private val properties: HoldingSyncProperties,
) {
    @Scheduled(
        fixedDelayString = "\${quantlog.holding-sync.interval-millis:10000}",
        // 다른 스케줄러들은 첫 동기화가 끝나야 움직이므로(hasUnsyncedTrade) 가장 먼저 시작한다.
        initialDelayString = "\${quantlog.holding-sync.initial-delay-millis:5000}",
    )
    fun run() {
        if (properties.enabled) syncNow()
    }

    /** 체결이 확인되면 10초 주기를 기다리지 않고 바로 잔고를 다시 받는다. */
    @EventListener
    fun onTradeFilled(event: TradeFilledEvent) {
        if (properties.enabled) syncNow()
    }

    @Synchronized
    fun syncNow() {
        val fetchedAt = Instant.now()
        val kis =
            try {
                broker.holdings(Market.KR) + broker.holdings(Market.NASDAQ)
            } catch (e: Exception) {
                log.warn(e) { "[잔고 동기화] KIS 잔고 조회 실패, 이번 회차 건너뜀" }
                return
            }
        holdingSync.sync(kis, fetchedAt)
    }
}

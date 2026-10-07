package com.quantlog.trading

import com.quantlog.broker.BrokerClient
import com.quantlog.broker.FillNotice
import com.quantlog.broker.Market
import com.quantlog.position.HoldingSyncService
import com.quantlog.position.TradeFilledEvent
import mu.KotlinLogging
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

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
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "holding-sync").apply { isDaemon = true } }
    private val running = AtomicBoolean(false)

    @Scheduled(
        fixedDelayString = "\${quantlog.holding-sync.interval-millis:10000}",
        // 다른 스케줄러들은 첫 동기화가 끝나야 움직이므로(hasUnsyncedTrade) 가장 먼저 시작한다.
        initialDelayString = "\${quantlog.holding-sync.initial-delay-millis:5000}",
    )
    fun run() {
        if (!properties.enabled || !running.compareAndSet(false, true)) return
        // 스프링 스케줄러는 스레드 하나를 모든 @Scheduled 가 나눠 쓴다. 느린 KIS 호출 하나가 잔고 동기화를 몇 분씩 막은 적이 있어
        // (2026-10-07: 화면 보유 종목이 갱신 안 됨) 동기화는 전용 스레드에서 돌리고, 이전 회차가 안 끝났으면 건너뛴다.
        executor.execute {
            try {
                syncNow()
            } finally {
                running.set(false)
            }
        }
    }

    /** 체결이 확인되면 10초 주기를 기다리지 않고 바로 잔고를 다시 받는다. */
    @EventListener
    fun onTradeFilled(event: TradeFilledEvent) {
        if (properties.enabled) syncNow()
    }

    /** 체결통보가 오면 KIS 잔고 조회(3~8초)를 기다리지 않고 그 체결만큼 바로 DB 를 고친다. 틀리면 다음 동기화가 바로잡는다. */
    @EventListener
    fun onFillNotice(notice: FillNotice) {
        if (!properties.enabled) return
        runCatching { holdingSync.applyFill(notice) }
            .onFailure { log.warn(it) { "[체결통보 반영] 실패 — 다음 KIS 잔고 동기화가 바로잡는다: ${notice.summary()}" } }
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

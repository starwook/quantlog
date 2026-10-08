package com.quantlog.trading

import com.quantlog.gatewayclient.BalanceProjector
import com.quantlog.gatewayclient.FillProjector
import com.quantlog.gatewayclient.GatewayNotified
import mu.KotlinLogging
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private val log = KotlinLogging.logger {}

@ConfigurationProperties(prefix = "quantlog.holding-sync")
data class HoldingSyncProperties(
    val enabled: Boolean = true,
)

/**
 * 게이트웨이가 기록한 체결 원장·잔고 스냅샷을 DB(account_holding·trade)에 반영하는 실행기. 증권사 앱에서 직접 거래한 물량도 잔고 스냅샷에 있으므로 따라온다.
 * 현재가도 같이 저장돼 화면은 증권사를 부르지 않는다. 화면을 열어야만 동기화되면 안 된다(2026-09-30 사용자 지적: 화면을 켜두고 앱에서 사면 어긋남).
 * 게이트웨이의 알림(웹소켓)이 오면 바로 돌고, 알림이 없어도 주기적으로 DB 를 확인한다 — 알림은 빠른 신호일 뿐 정본은 DB 다.
 *
 * 동기화 불일치 보고 불필요: 증권사와 DB 를 비교하는 건 [com.quantlog.position.HoldingSyncService] 이고, 불일치 보고도 거기서 한다. 여기는 주기와 스레드만 맡는다.
 */
@Component
class HoldingSyncScheduler(
    private val fills: FillProjector,
    private val balance: BalanceProjector,
    private val properties: HoldingSyncProperties,
) {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "holding-sync").apply { isDaemon = true } }
    private val running = AtomicBoolean(false)

    @Scheduled(
        fixedDelayString = "\${quantlog.holding-sync.interval-millis:1000}",
        // 다른 스케줄러들은 첫 동기화가 끝나야 움직이므로(hasUnsyncedTrade) 가장 먼저 시작한다.
        initialDelayString = "\${quantlog.holding-sync.initial-delay-millis:3000}",
    )
    fun run() {
        if (!properties.enabled || !running.compareAndSet(false, true)) return
        // 스프링 스케줄러는 스레드 하나를 모든 @Scheduled 가 나눠 쓴다. 느린 작업 하나가 반영을 막지 않게 전용 스레드에서 돌리고,
        // 이전 회차가 안 끝났으면 건너뛴다.
        executor.execute {
            try {
                syncNow()
            } finally {
                running.set(false)
            }
        }
    }

    /** 게이트웨이가 새 체결·잔고를 기록했다고 알리면 주기를 기다리지 않고 바로 돈다. */
    @EventListener
    fun onGatewayNotified(event: GatewayNotified) = run()

    @Synchronized
    fun syncNow() {
        runCatching { fills.project() }.onFailure { log.warn(it) { "[체결 반영] 원장 읽기 실패, 다음 회차에 재시도: ${it.message}" } }
        runCatching { balance.project() }.onFailure { log.warn(it) { "[잔고 반영] 스냅샷 읽기 실패, 다음 회차에 재시도: ${it.message}" } }
    }
}

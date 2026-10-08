package com.quantlog.gatewayclient

import com.quantlog.broker.Market
import com.quantlog.broker.RealtimePriceFeed
import com.quantlog.position.FillBackfillService
import mu.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors

private val log = KotlinLogging.logger {}

/** 앱이 이해하는 게이트웨이 계약 버전. 게이트웨이(`gateway_instance.contract_version`)와 다르면 에러로 알린다 — docs/contracts/. */
const val APP_CONTRACT_VERSION = 1

/**
 * 게이트웨이 하트비트(`gateway_instance`)를 주기적으로 읽어 세 가지를 한다.
 * 1. 게이트웨이가 멈췄는지 감시한다 — 하트비트가 [STALE] 보다 오래되면 `[게이트웨이 끊김]` ERROR(→ `/errors`·웹훅). 매매는 계속 시도하지만 주문 HTTP 가 실패할 것이다.
 * 2. 게이트웨이의 증권사 웹소켓이 새로 붙었거나(재연결) 게이트웨이가 새로 떴으면 그동안 게이트웨이가 놓친 체결이 있는지 KIS 와 대조한다([FillBackfillService]).
 * 3. [RealtimePriceFeed] 를 구현한다 — 게이트웨이가 지금 실시간으로 보고 있는 종목이면 폴링 쪽이 건너뛴다.
 *
 * 동기화 불일치 보고 불필요: 증권사와 DB 의 내용을 비교하지 않는다. 하트비트 상태만 보고 대조를 시작시킨다(비교·보고는 [FillBackfillService] 가 한다).
 */
@Component
class GatewayWatcher(
    private val rows: GatewayInstanceRowRepository,
    private val backfill: FillBackfillService,
    private val properties: GatewayClientProperties,
    private val clock: Clock = Clock.systemUTC(),
) : RealtimePriceFeed {
    @Volatile private var view: View = View.DOWN

    private var lastKey: Pair<String, Instant?>? = null
    private var staleSince: Instant? = null
    private var lastAlarmAt: Instant = Instant.EPOCH
    private var versionAlarmed = false
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "gateway-watcher-backfill").apply { isDaemon = true } }

    private data class View(val alive: Boolean, val live: Set<String>) {
        companion object {
            val DOWN = View(false, emptySet())
        }
    }

    override fun isLive(
        market: Market,
        symbol: String,
    ): Boolean = market == Market.KR && view.alive && symbol in view.live

    @Scheduled(fixedDelay = 2_000, initialDelay = 2_000)
    fun check() {
        if (!properties.enabled) return
        val row =
            runCatching { rows.findById(1L).orElse(null) }.getOrElse {
                log.warn { "[게이트웨이 감시] 하트비트 읽기 실패: ${it.message}" }
                return
            }
        val now = clock.instant()
        if (row == null || Duration.between(row.heartbeatAt, now) > STALE) {
            view = View.DOWN
            raiseStale(row, now)
            return
        }
        if (staleSince != null) {
            log.info { "[게이트웨이 감시] 게이트웨이 하트비트 복구됨" }
            staleSince = null
        }
        if (row.contractVersion != APP_CONTRACT_VERSION && !versionAlarmed) {
            versionAlarmed = true
            log.error { "[게이트웨이 계약] 계약 버전이 다르다: 게이트웨이=${row.contractVersion} 앱=$APP_CONTRACT_VERSION" }
        }
        val live = if (row.wsConnectedAt == null) emptySet() else row.liveSymbols.orEmpty().split(',').filter { it.isNotBlank() }.toSet()
        view = View(true, live)

        val key = row.instanceId to row.wsConnectedAt
        val previous = lastKey
        lastKey = key
        if (row.wsConnectedAt != null && key != previous) {
            log.info { "[게이트웨이 감시] 게이트웨이 실시간 연결 시작/재연결 감지 — 놓친 체결이 있는지 대조한다 (인스턴스=${row.instanceId})" }
            executor.execute { runCatching { backfill.backfill() }.onFailure { log.warn(it) { "[체결 대조] 재연결 후 대조 실패: ${it.message}" } } }
        }
    }

    private fun raiseStale(
        row: GatewayInstanceRow?,
        now: Instant,
    ) {
        if (staleSince == null) staleSince = now
        if (Duration.between(lastAlarmAt, now) < ALARM_EVERY) return
        lastAlarmAt = now
        val last = row?.heartbeatAt?.toString() ?: "기록 없음"
        log.error { "[게이트웨이 끊김] 게이트웨이 하트비트가 ${STALE.seconds}초 넘게 없다 (마지막: $last). 주문·시세·체결 기록이 멈춰 있을 수 있다" }
    }

    private companion object {
        val STALE: Duration = Duration.ofSeconds(20)
        val ALARM_EVERY: Duration = Duration.ofMinutes(1)
    }
}

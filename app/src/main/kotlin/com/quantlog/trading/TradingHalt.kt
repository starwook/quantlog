package com.quantlog.trading

import com.quantlog.sync.SyncEventKind
import com.quantlog.sync.SyncEventRecorder
import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant

private val log = KotlinLogging.logger {}

/** 거래를 멈추는 이유. 이유마다 따로 걸고 풀며, 하나라도 걸려 있으면 멈춘다. */
enum class HaltReason(val label: String) {
    /** 한투 체결통보를 못 받는 중(게이트웨이 `kis_ws` false). 다시 구독됐다는 알림으로만 풀린다. */
    KIS_DOWN("한투 체결통보 끊김"),

    /** 체결통보가 빠졌을 수 있어 체결조회로 맞추는 중(재연결·앱 시작·게이트웨이 재연결). 조회가 성공하면 풀린다. */
    RECONCILE("체결조회로 맞추는 중"),
}

/**
 * 거래 정지 스위치 — 체결통보가 빠졌을 수 있는 동안 **모든 주문**(자동·수동, 매수·매도)을 막는다(2026-10-14 사용자 결정: "끊을려면 다 끊어야지").
 * 막는 자리는 [RiskGuard] 하나라서 앞으로 생기는 주문 경로도 따로 손대지 않아도 막힌다. 취소는 막지 않는다(주문을 줄이는 쪽이라).
 * 걸고 푸는 건 사건으로만 한다 — 시계로 풀지 않는다. 멈춘 때·다시 연 때는 [SyncEventRecorder] 에 남긴다(매시 있는 일이라 로그는 INFO — WARN 은 웹훅 알림이 된다).
 */
@Component
class TradingHalt(
    /** 없으면(테스트) 로그만 남긴다. */
    private val recorder: SyncEventRecorder? = null,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val reasons = linkedMapOf<HaltReason, String>()
    private var haltedSince: Instant? = null

    /** 멈춰 있으면 그 이유(사람이 읽는 문장), 아니면 null. */
    val reason: String?
        @Synchronized get() = reasons.entries.takeIf { it.isNotEmpty() }?.joinToString(", ") { "${it.key.label}(${it.value})" }

    val active: Boolean
        @Synchronized get() = reasons.isNotEmpty()

    @Synchronized
    fun holds(reason: HaltReason): Boolean = reason in reasons

    @Synchronized
    fun hold(
        reason: HaltReason,
        detail: String,
    ) {
        val wasActive = reasons.isNotEmpty()
        reasons[reason] = detail
        if (wasActive) return
        val now = clock.instant()
        haltedSince = now
        // 매시 정각마다 있는 일이라 경고(=웹훅 알림)로 찍지 않는다. 기록은 sync_event 에 남는다.
        log.info { "[거래 정지] ${reason.label}: $detail — 모든 주문을 막는다" }
        recorder?.record(SyncEventKind.HALT, reason.label, detail, now)
    }

    @Synchronized
    fun release(
        reason: HaltReason,
        summary: String,
    ) {
        if (reasons.remove(reason) == null || reasons.isNotEmpty()) return
        val now = clock.instant()
        val millis = haltedSince?.let { Duration.between(it, now).toMillis() }
        haltedSince = null
        val detail = "${reason.label} 해제: $summary (정지 ${millis ?: "?"}ms)"
        log.info { "[거래 재개] $detail" }
        recorder?.record(SyncEventKind.RESUME, reason.label, detail, now)
    }
}

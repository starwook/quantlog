package com.quantlog.trading

import com.quantlog.gatewayclient.KisFillNoticeLinkChanged
import com.quantlog.position.FillReconcileRequested
import com.quantlog.position.FillReconcileService
import com.quantlog.sync.SyncEventKind
import com.quantlog.sync.SyncEventRecorder
import mu.KotlinLogging
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private val log = KotlinLogging.logger {}

/**
 * 체결조회로 맞추는 일([FillReconcileService])을 언제 돌릴지 정하고, 그동안 거래를 멈춘다([TradingHalt]). 시계로 판정하지 않고 사건으로만 움직인다.
 * - **한투 체결통보 끊김**(`kis_ws` false): 바로 모든 주문을 멈춘다. 다시 구독되면(true) 체결조회로 맞춘 뒤에야 연다.
 * - **재연결·앱 시작·게이트웨이 재연결 감지**([FillReconcileRequested] halting): 멈추고 맞춘 뒤 연다. 조회가 실패하면 1초마다 다시 시도하며 계속 멈춰 있다.
 * - **주문이 불확실함**(취소 거부·취소됨, halting 아님)과 **3분 주기**: 멈추지 않고 맞추기만 한다(실패하면 다음 기회에).
 * 맞추는 도중에 새 정지 요청이 오면 그 조회로는 풀지 않고 한 번 더 맞춘다(요청 이후의 조회여야 빠진 체결을 다 본다).
 * 끊김·재연결·조회 실패는 [SyncEventRecorder] 에 한 건씩 남긴다.
 *
 * 동기화 불일치 보고 불필요: 언제 돌릴지만 정한다. 증권사와 DB 를 비교하고 보고하는 건 [FillReconcileService]→[com.quantlog.position.HoldingFillApplier.applyInquiry] 다.
 */
@Component
class FillReconcileScheduler(
    private val service: FillReconcileService,
    private val halt: TradingHalt,
    private val recorder: SyncEventRecorder,
) {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "fill-reconcile").apply { isDaemon = true } }
    private val queued = AtomicBoolean(false)

    /** 정지 요청마다 오른다. 조회를 시작할 때의 값과 끝났을 때 값이 같아야 정지를 푼다. */
    private val generation = AtomicLong()

    @Volatile private var lastTrigger = ""

    @EventListener(ApplicationReadyEvent::class)
    fun onStart() = reconcileHalting("앱 시작")

    @EventListener
    fun onRequested(event: FillReconcileRequested) = if (event.halting) reconcileHalting(event.trigger) else request(event.trigger)

    @EventListener
    fun onKisLink(event: KisFillNoticeLinkChanged) {
        if (!event.live) {
            halt.hold(HaltReason.KIS_DOWN, "게이트웨이가 알림 (끊긴 시각 ${event.downSince ?: event.at})")
            recorder.record(
                SyncEventKind.KIS_DOWN,
                "한투 실시간",
                "체결통보를 못 받는다 — 끊긴 시각 ${event.downSince ?: event.at}",
                event.downSince ?: event.at,
            )
            return
        }
        val gap = event.downSince?.let { "끊긴 지 ${Duration.between(it, event.at).toMillis()}ms 만에 재구독" } ?: "구독됨"
        recorder.record(SyncEventKind.KIS_UP, "한투 실시간", gap, event.at)
        // 맞추기 정지를 먼저 걸고 끊김 정지를 푼다 — 그 사이 한순간도 열리지 않게.
        reconcileHalting("한투 재연결 ($gap)")
        halt.release(HaltReason.KIS_DOWN, "한투 체결통보 재구독")
    }

    /** 3분마다: 멈추지 않고 확인만 한다. 연결이 신호 없이 조용히 죽어 재연결 신호가 안 오는 경우의 마지막 안전망이다. */
    @Scheduled(fixedDelay = 180_000, initialDelay = 20_000)
    fun periodic() = request("3분 주기 대조")

    fun reconcileHalting(trigger: String) {
        generation.incrementAndGet()
        halt.hold(HaltReason.RECONCILE, trigger)
        request(trigger)
    }

    fun request(trigger: String) {
        lastTrigger = trigger
        if (!queued.compareAndSet(false, true)) return
        executor.execute {
            queued.set(false)
            runUntilDone()
        }
    }

    private fun runUntilDone() {
        var failures = 0
        while (true) {
            val startedGeneration = generation.get()
            val trigger = lastTrigger
            val result = runCatching { service.reconcile() }
            result.onSuccess { r ->
                if (!halt.holds(HaltReason.RECONCILE)) {
                    if (r.corrected > 0) log.info { "[체결 대조] $trigger: 조회 ${r.checked}건, 보정 ${r.corrected}건" }
                    return
                }
                if (generation.get() != startedGeneration) return@onSuccess
                val retried = if (failures > 0) ", 실패 ${failures}회 뒤" else ""
                halt.release(HaltReason.RECONCILE, "$trigger — 조회 ${r.checked}건, 보정 ${r.corrected}건, 끝남 ${r.settled}건$retried")
                return
            }
            result.onFailure { e ->
                failures++
                val halting = halt.holds(HaltReason.RECONCILE)
                if (failures == 1) {
                    // 기록이 실패해도 재시도는 이어져야 한다 — 이 루프가 죽으면 거래 정지가 영영 안 풀린다.
                    runCatching { recorder.record(SyncEventKind.INQUIRY_FAILED, trigger, e.message ?: e.javaClass.simpleName) }
                    val next = if (halting) "거래를 멈춘 채 1초마다 다시 시도" else "다음 기회에 다시"
                    log.warn { "[체결 대조] $trigger: 한투 체결조회 실패 — $next: ${e.message}" }
                }
                if (!halting) return
                Thread.sleep(RETRY.toMillis())
            }
        }
    }

    private companion object {
        val RETRY: Duration = Duration.ofSeconds(1)
    }
}

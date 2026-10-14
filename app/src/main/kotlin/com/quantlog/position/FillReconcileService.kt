package com.quantlog.position

import com.quantlog.broker.BrokerClient
import com.quantlog.broker.CallPriority
import com.quantlog.broker.Market
import com.quantlog.broker.OrderFillTotal
import com.quantlog.common.KST
import org.springframework.stereotype.Service
import java.time.Instant

/** [FillReconcileService.reconcile] 한 번의 결과. */
data class ReconcileResult(
    /** 한투 체결조회로 확인한 주문 수. */
    val checked: Int = 0,
    /** 체결통보가 빠져 있어 체결조회 값으로 보유·평단을 고친 주문 수. */
    val corrected: Int = 0,
    /** 이번에 끝난 것(전량 체결 또는 취소, 남은 수량 0)으로 확인한 주문 수. */
    val settled: Int = 0,
)

/** 체결통보가 빠졌을 수 있어 한투 체결조회로 맞추라는 요청. [halting] 이면 맞출 때까지 모든 주문을 멈춘다. */
data class FillReconcileRequested(val trigger: String, val halting: Boolean)

/**
 * 오늘 우리 주문 중 아직 끝났다고 확인되지 않은 것(`order_fill.settled` 아님)을 한투 체결조회로 확인해, 주문별 체결 합계(`order_fill`)와
 * 보유 현황에 반영한다([HoldingFillApplier.applyInquiry]). 체결통보는 한투 웹소켓이 끊긴 동안 빠지고 다시 오지 않으므로(2026-10-14 실측: 매시 정각에 끊긴다)
 * 이 조회가 빠진 체결을 채운다. 어긋났던 주문은 [com.quantlog.sync.SyncMismatchReporter] 로 보고된다(applyInquiry 안).
 *
 * - 확인할 주문이 적으면 주문번호로 하나씩, 많으면 오늘 전체를 받는다(한 쪽에 15건, 1.1초 간격 — 하나씩 부르는 것보다 빠를 때).
 * - 체결조회에 아직 안 잡힌 주문은 그대로 둔다(다음 조회가 본다). 남은 수량이 0 이면 끝난 주문으로 적고, 덜 체결된 채 끝났으면 매매 기록을 취소로 바꾼다.
 * - 한투 호출은 잠금 밖에서, 반영은 [HoldingWriteLock] 안에서 한다. 조회가 실패하면 예외를 그대로 던진다(부른 쪽이 다시 시도한다).
 * - 조회는 즉발 우선순위로 보낸다 — 거래를 멈추고 기다리는 조회라 분봉·잔고 같은 주기 호출 뒤에 줄 서면 안 된다(broker/CallPriority.kt).
 * - 먼저 오늘 주문별 체결 합계가 원장으로 채워져 있는지 확인한다([HoldingFillApplier.rebuildToday]). 이 기능을 장중에 처음 배포한 날 앱이 뜨자마자 조회가
 *   먼저 돌면, 이미 보유에 들어간 체결을 "통보 0주"로 보고 한 번 더 얹기 때문이다.
 */
@Service
class FillReconcileService(
    private val broker: BrokerClient,
    private val tradeRepository: TradeRepository,
    private val fillStates: OrderFillStates,
    private val applier: HoldingFillApplier,
    private val tradeService: TradeService,
    private val writeLock: HoldingWriteLock,
) {
    fun reconcile(now: Instant = Instant.now()): ReconcileResult {
        val today = now.atZone(KST).toLocalDate()
        writeLock.run { applier.rebuildToday(now) }
        val candidates =
            tradeRepository.findAll().filter {
                it.market == Market.KR && it.executedAt.atZone(KST).toLocalDate() == today && !fillStates.settled(it.orderNo, today)
            }
        if (candidates.isEmpty()) return ReconcileResult()

        val totals: Map<String, OrderFillTotal?> =
            CallPriority.urgent {
                if (candidates.size > BULK_THRESHOLD) {
                    val all = broker.todayOrderFills(Market.KR).associateBy { it.orderNo }
                    candidates.associate { it.orderNo to all[it.orderNo] }
                } else {
                    candidates.associate { it.orderNo to broker.orderFillTotal(Market.KR, it.orderNo) }
                }
            }

        var corrected = 0
        var settled = 0
        candidates.forEach { trade ->
            val total = totals[trade.orderNo] ?: return@forEach
            writeLock.run {
                if (applier.applyInquiry(trade, total, now) != null) corrected++
                if (fillStates.settled(trade.orderNo, today)) {
                    settled++
                    if (!trade.canceled && fillStates.filled(trade.orderNo, today).quantity < trade.quantity) {
                        tradeService.markCanceled(
                            trade,
                        )
                    }
                }
            }
        }
        return ReconcileResult(candidates.size, corrected, settled)
    }

    private companion object {
        /** 확인할 주문이 이보다 많으면 하나씩 부르지 않고 오늘 전체를 받는다. */
        const val BULK_THRESHOLD = 3
    }
}

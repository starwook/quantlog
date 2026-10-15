package com.quantlog.trading

import com.quantlog.broker.BrokerClient
import com.quantlog.broker.CallPriority
import com.quantlog.broker.CancelRequest
import com.quantlog.broker.OrderReceipt
import com.quantlog.broker.OrderRequest
import com.quantlog.position.FillProgress
import com.quantlog.position.TradeService
import mu.KotlinLogging
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap

private val log = KotlinLogging.logger {}

/**
 * "주문을 냈으면 그 주문이 끝났다고 확인될 때까지 그 종목 판정을 미루고, [fillTimeout] 이 지나도 체결이 다 안 됐으면 주문을 취소한다"
 * 를 한곳에 모은 것. 마틴게일 매수·청산 매도·진입 주문처럼 틱마다 판정하는 주문 경로가 같이 쓴다. 종목 키는 `"$market:$symbol"`.
 *
 * **대기는 주문이 끝났다고 확인될 때만 풀린다**([FillProgress.isOrderClosed] — 주문수량만큼 체결을 보유에 반영했거나, 한투 체결조회가 남은 수량 0 으로 확인).
 * 시간이 지났다고 풀지 않는다 — 체결통보가 빠진 채(한투 웹소켓 끊김) 풀면 낡은 보유·평단으로 다음 주문이 나간다(2026-10-14 233740 중복 매수).
 * - 취소가 거부되면(이미 체결됐을 수 있다 — "정정/취소할 수량이 없습니다") 체결조회를 요청하고([requestCheck]), [CANCEL_RETRY] 마다 취소를 다시 시도한다.
 * - 취소가 성공해도 바로 풀지 않는다. 취소 전에 일부 체결됐는데 통보가 빠졌을 수 있어, 체결조회가 끝난 주문으로 확인할 때 풀린다(바로 요청한다).
 * [label] 은 로그에 찍을 이름(예: "마틴게일", "청산").
 */
class PendingOrders(
    private val label: String,
    private val broker: BrokerClient,
    private val tradeService: TradeService,
    private val holdingSync: FillProgress,
    private val fillTimeout: Duration,
    /** 이 주문이 어떻게 끝났는지 한투 체결조회로 확인해 달라는 요청(멈추지 않고). 인자는 기록용 사유. */
    private val requestCheck: (String) -> Unit,
) {
    private class Pending(val request: OrderRequest, val receipt: OrderReceipt, val placedAt: Instant) {
        /** 마지막으로 취소를 시도한 시각. 거부가 이어질 때 매초 두드리지 않으려고 쓴다. */
        var lastCancelTryAt: Instant? = null

        /** 취소가 성공했다. 더 취소하지 않고, 체결조회가 끝난 주문으로 확인하기를 기다린다. */
        var canceled = false
    }

    private val pending = ConcurrentHashMap<String, Pending>()

    fun track(
        key: String,
        request: OrderRequest,
        receipt: OrderReceipt,
        now: Instant,
    ) {
        pending[key] = Pending(request, receipt, now)
    }

    /** 이 종목에 끝나지 않은 주문이 있으면 true — 판정을 건너뛰어야 한다. 끝났으면 대기를 풀고 false. */
    fun isWaiting(key: String): Boolean {
        val order = pending[key] ?: return false
        if (!holdingSync.isOrderClosed(order.receipt.orderNo)) return true
        pending.remove(key)
        return false
    }

    /** [keyLock] 으로 종목 판정과 겹치지 않게 [expire] 한다. */
    fun expire(
        now: ZonedDateTime,
        keyLock: KeyedLock,
    ) = expire(now) { key, action -> keyLock.runIfFree(key, action) }

    /**
     * 끝난 주문은 대기에서 빼고, 시간이 지난 미체결 주문은 취소한다(주기 호출). 취소는 같은 종목의 판정과 겹치지 않도록 [withKeyLock] 안에서 한다 —
     * 잠금을 못 잡으면 건너뛰고 다음 주기에 다시 한다.
     */
    fun expire(
        now: ZonedDateTime,
        withKeyLock: (key: String, action: () -> Unit) -> Unit,
    ) {
        pending.forEach { (key, order) ->
            if (holdingSync.isOrderClosed(order.receipt.orderNo)) {
                pending.remove(key)
                return@forEach
            }
            if (order.canceled) return@forEach
            val age = Duration.between(order.placedAt, now.toInstant())
            if (age < fillTimeout) return@forEach
            val lastTry = order.lastCancelTryAt
            if (lastTry != null && Duration.between(lastTry, now.toInstant()) < CANCEL_RETRY) return@forEach
            withKeyLock(key) {
                order.lastCancelTryAt = now.toInstant()
                cancel(order)
            }
        }
    }

    private fun cancel(order: Pending) {
        val request = order.request
        val orderNo = order.receipt.orderNo
        runCatching {
            CallPriority.urgent {
                broker.cancelOrder(
                    CancelRequest(request.market, request.symbol, orderNo, order.receipt.branchNo, request.quantity, request.limitPrice),
                )
            }
        }
            .onSuccess {
                order.canceled = true
                tradeService.findByOrderNo(request.market, orderNo)?.let(tradeService::markCanceled)
                log.info { "[$label 취소] ${request.market} ${request.symbol} 미체결 주문 $orderNo 을 취소했다 — 체결조회로 끝난 것을 확인하면 다시 판정" }
                requestCheck("$label 취소한 주문 $orderNo 확인")
            }
            .onFailure {
                // 취소 실패 사유를 나중에 찾을 수 있게 주문번호·경과·응답 메시지를 한 줄에 남긴다(오류 기록 화면에 묶여 쌓인다).
                log.warn {
                    "[$label] ${request.symbol} 주문 $orderNo 취소 실패(${Duration.between(order.placedAt, Instant.now()).seconds}초 경과, " +
                        "이미 체결됐을 수 있다): ${it.message} — 체결조회로 확인한다"
                }
                requestCheck("$label 취소 거부 주문 $orderNo 확인")
            }
    }

    private companion object {
        val CANCEL_RETRY: Duration = Duration.ofSeconds(5)
    }
}

package com.quantlog.trading

import com.quantlog.broker.BrokerClient
import com.quantlog.broker.CallPriority
import com.quantlog.broker.CancelRequest
import com.quantlog.broker.OrderReceipt
import com.quantlog.broker.OrderRequest
import com.quantlog.position.HoldingSyncService
import com.quantlog.position.TradeService
import mu.KotlinLogging
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap

private val log = KotlinLogging.logger {}

/**
 * "주문을 냈으면 체결이 확인될 때까지 그 종목 판정을 미루고, [fillTimeout] 이 지나도 미체결이면 주문을 취소해서 다시 판정하게 한다"
 * 를 한곳에 모은 것. 마틴게일 매수·청산 매도처럼 틱마다 판정하는 주문 경로가 같이 쓴다. 종목 키는 `"$market:$symbol"`.
 * 체결 확인은 체결통보([HoldingSyncService.isOrderFilled])로 한다. 취소가 거부되면 이미 체결됐을 수 있어 통보를 계속 기다리되,
 * [GIVE_UP] 이 지나면 포기하고 판정을 다시 연다(보유는 다음 KIS 잔고 동기화가 바로잡는다).
 * [label] 은 로그에 찍을 이름(예: "마틴게일", "청산").
 */
class PendingOrders(
    private val label: String,
    private val broker: BrokerClient,
    private val tradeService: TradeService,
    private val holdingSync: HoldingSyncService,
    private val fillTimeout: Duration,
) {
    private class Pending(val request: OrderRequest, val receipt: OrderReceipt, val placedAt: Instant) {
        /** 마지막으로 취소를 시도한 시각. 거부가 이어질 때 매초 두드리지 않으려고 쓴다. */
        var lastCancelTryAt: Instant? = null
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

    /** 이 종목에 체결 확인 대기 중인 주문이 있으면 true — 판정을 건너뛰어야 한다. 체결이 확인됐으면 대기를 풀고 false. */
    fun isWaiting(key: String): Boolean {
        val order = pending[key] ?: return false
        if (!holdingSync.isOrderFilled(order.receipt.orderNo)) return true
        pending.remove(key)
        return false
    }

    /**
     * 시간이 지난 미체결 주문을 취소한다(주기 호출). 취소는 같은 종목의 판정과 겹치지 않도록 [withKeyLock] 안에서 한다 —
     * 잠금을 못 잡으면 건너뛰고 다음 주기에 다시 한다.
     */
    fun expire(
        now: ZonedDateTime,
        withKeyLock: (key: String, action: () -> Unit) -> Unit,
    ) {
        pending.forEach { (key, order) ->
            if (holdingSync.isOrderFilled(order.receipt.orderNo)) {
                pending.remove(key)
                return@forEach
            }
            val age = Duration.between(order.placedAt, now.toInstant())
            if (age < fillTimeout) return@forEach
            if (age > GIVE_UP) {
                log.warn { "[$label] ${order.request.symbol} 주문 ${order.receipt.orderNo} 의 체결·취소를 확인하지 못해 포기한다" }
                pending.remove(key)
                return@forEach
            }
            val lastTry = order.lastCancelTryAt
            if (lastTry != null && Duration.between(lastTry, now.toInstant()) < CANCEL_RETRY) return@forEach
            withKeyLock(key) {
                order.lastCancelTryAt = now.toInstant()
                cancel(key, order)
            }
        }
    }

    private fun cancel(
        key: String,
        order: Pending,
    ) {
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
                tradeService.findByOrderNo(request.market, orderNo)?.let(tradeService::markCanceled)
                pending.remove(key)
                log.info { "[$label 취소] ${request.market} ${request.symbol} 미체결 주문 $orderNo 을 취소했다 — 다음 틱부터 다시 판정" }
            }
            .onFailure { log.warn(it) { "[$label] ${request.symbol} 주문 $orderNo 취소 실패(이미 체결됐을 수 있다) — 체결통보를 기다린다" } }
    }

    private companion object {
        val GIVE_UP: Duration = Duration.ofMinutes(2)
        val CANCEL_RETRY: Duration = Duration.ofSeconds(5)
    }
}

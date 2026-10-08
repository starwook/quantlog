package com.quantlog.trading

import com.quantlog.broker.BrokerClient
import com.quantlog.broker.CallPriority
import com.quantlog.broker.CancelRequest
import com.quantlog.broker.OrderReceipt
import com.quantlog.broker.OrderRequest
import com.quantlog.broker.OrderStatus
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
 * 첫 취소 시도 뒤 [GIVE_UP] 이 지나면 KIS 에 실제 상태를 물어, 체결됐으면 대기를 풀고 아직 호가창에 남아 있으면 취소를 계속 시도하며,
 * 그것도 모르겠으면 포기하고 판정을 다시 연다(보유는 다음 KIS 잔고 동기화가 바로잡는다). 한 종목에 주문이 둘 겹치는 건 새 주문을 [isWaiting] 이
 * 막아서 포기한 뒤에 남은 주문뿐인데, 위 상태 확인이 그 경우를 막는다.
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
        /** 처음 취소를 시도한 시각. 포기 시한([GIVE_UP])은 주문한 때가 아니라 이때부터 센다. */
        var firstCancelTryAt: Instant? = null

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
            val lastTry = order.lastCancelTryAt
            if (lastTry != null && Duration.between(lastTry, now.toInstant()) < CANCEL_RETRY) return@forEach
            // 포기는 취소를 시도해 보고도 안 될 때만 한다. 스케줄러가 느린 KIS 호출에 막혀 점검이 몇 분 늦게 돌면 주문한 지 2분이 훌쩍
            // 지나 있는데, 그때 취소도 안 해 보고 포기하면 호가창에 남은 주문이 나중에 체결된다(2026-10-08 226490 마틴게일 44주).
            val firstTry = order.firstCancelTryAt
            val giveUpDue = firstTry != null && Duration.between(firstTry, now.toInstant()) > GIVE_UP
            if (age > HARD_LIMIT) {
                giveUp(key, order, "취소 시도 없이 ${HARD_LIMIT.toMinutes()}분 경과")
                return@forEach
            }
            if (giveUpDue) {
                // 포기하기 전에 KIS 에 실제 상태를 묻는다 — 체결됐으면 끝, 아직 호가창에 남아 있으면 포기하지 말고 취소를 계속 시도한다.
                when (val status = statusOf(order)) {
                    is OrderStatus.Filled -> {
                        log.info { "[$label] ${order.request.symbol} 주문 ${order.receipt.orderNo} 은 KIS 조회로 체결이 확인돼 대기를 푼다" }
                        pending.remove(key)
                        return@forEach
                    }
                    OrderStatus.Open -> Unit
                    else -> {
                        giveUp(key, order, "KIS 조회: ${status ?: "실패"}")
                        return@forEach
                    }
                }
            }
            withKeyLock(key) {
                order.lastCancelTryAt = now.toInstant()
                if (order.firstCancelTryAt == null) order.firstCancelTryAt = now.toInstant()
                cancel(key, order)
            }
        }
    }

    private fun statusOf(order: Pending): OrderStatus? =
        runCatching { broker.orderStatus(order.request.market, order.receipt.orderNo, order.request.quantity) }.getOrNull()

    private fun giveUp(
        key: String,
        order: Pending,
        detail: String,
    ) {
        log.warn { "[$label] ${order.request.symbol} 주문 ${order.receipt.orderNo} 의 체결·취소를 확인하지 못해 포기한다 ($detail)" }
        pending.remove(key)
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
            .onFailure {
                // 취소 실패 사유를 나중에 찾을 수 있게 주문번호·경과·응답 메시지를 한 줄에 남긴다(오류 기록 화면에 묶여 쌓인다).
                log.warn(it) {
                    "[$label] ${request.symbol} 주문 $orderNo 취소 실패(${Duration.between(order.placedAt, Instant.now()).seconds}초 경과, " +
                        "이미 체결됐을 수 있다): ${it.message} — 체결통보를 기다린다"
                }
            }
    }

    private companion object {
        /** 첫 취소 시도부터 이만큼 지나도 체결·취소를 확인 못 하면 포기한다. */
        val GIVE_UP: Duration = Duration.ofMinutes(2)

        /** 취소 시도 자체를 못 하는 상태(종목 잠금을 계속 못 잡음 등)로 이만큼 지나면 포기한다 — 판정이 영영 막히지 않게. */
        val HARD_LIMIT: Duration = Duration.ofMinutes(10)
        val CANCEL_RETRY: Duration = Duration.ofSeconds(5)
    }
}

package com.quantlog.trading

import com.quantlog.broker.BrokerClient
import com.quantlog.broker.CancelRequest
import com.quantlog.broker.Market
import com.quantlog.broker.OrderReceipt
import com.quantlog.broker.OrderRequest
import com.quantlog.broker.OrderStatus
import com.quantlog.broker.Side
import com.quantlog.position.HoldingSyncService
import com.quantlog.position.TradeService
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 체결 확인 대기·미체결 취소·포기 ([PendingOrders.expire]). */
class PendingOrdersTest {
    private val broker = Mockito.mock(BrokerClient::class.java)
    private val tradeService = Mockito.mock(TradeService::class.java)
    private val holdingSync = Mockito.mock(HoldingSyncService::class.java)
    private val pending = PendingOrders("마틴게일", broker, tradeService, holdingSync, Duration.ofSeconds(10))

    private val key = "KR:226490"
    private val request = OrderRequest(Market.KR, "226490", Side.BUY, 44, BigDecimal("69595"))
    private val placedAt = Instant.parse("2026-10-08T00:44:42Z")

    private fun at(secondsAfterOrder: Long) = ZonedDateTime.ofInstant(placedAt.plusSeconds(secondsAfterOrder), ZoneId.of("Asia/Seoul"))

    private val runLocked: (String, () -> Unit) -> Unit = { _, action -> action() }

    private fun <T> anyNonNull(): T = Mockito.any<T>()

    private fun track() = pending.track(key, request, OrderReceipt("0000011848", "ok", "00950"), placedAt)

    private fun cancelAttempts() = Mockito.mockingDetails(broker).invocations.count { it.method.name == "cancelOrder" }

    @Test
    fun `10초가 지나도 체결 확인이 안 되면 취소를 시도한다`() {
        track()

        pending.expire(at(11), runLocked)

        assertEquals(1, cancelAttempts())
        assertFalse(pending.isWaiting(key))
    }

    @Test
    fun `점검이 몇 분 늦게 돌아도 취소를 한 번도 안 했으면 포기하지 않고 먼저 취소한다`() {
        track()

        pending.expire(at(5 * 60), runLocked)

        assertEquals(1, cancelAttempts())
    }

    @Test
    fun `취소가 계속 거부되면 첫 시도로부터 2분 뒤에 포기하고 판정을 다시 연다`() {
        track()
        Mockito.doThrow(IllegalStateException("취소 거부")).`when`(broker).cancelOrder(anyNonNull<CancelRequest>())

        pending.expire(at(11), runLocked)
        assertTrue(pending.isWaiting(key))

        pending.expire(at(11 + 121), runLocked)
        assertFalse(pending.isWaiting(key))
    }

    @Test
    fun `종목 잠금을 계속 못 잡아 취소를 못 해도 10분이 지나면 포기한다`() {
        track()
        val neverLocked: (String, () -> Unit) -> Unit = { _, _ -> }

        pending.expire(at(5 * 60), neverLocked)
        assertTrue(pending.isWaiting(key))

        pending.expire(at(10 * 60 + 1), neverLocked)
        assertFalse(pending.isWaiting(key))
    }

    @Test
    fun `포기할 때가 돼도 KIS 에서 체결이 확인되면 포기하지 않고 대기만 푼다`() {
        track()
        Mockito.doThrow(IllegalStateException("취소 거부")).`when`(broker).cancelOrder(anyNonNull<CancelRequest>())
        Mockito.`when`(broker.orderStatus(Market.KR, "0000011848", 44)).thenReturn(OrderStatus.Filled(BigDecimal("69590")))

        pending.expire(at(11), runLocked)
        pending.expire(at(11 + 121), runLocked)

        assertFalse(pending.isWaiting(key))
    }

    @Test
    fun `포기할 때가 돼도 KIS 에서 아직 미체결이면 포기하지 않고 취소를 계속 시도한다`() {
        track()
        Mockito.doThrow(IllegalStateException("취소 거부")).`when`(broker).cancelOrder(anyNonNull<CancelRequest>())
        Mockito.`when`(broker.orderStatus(Market.KR, "0000011848", 44)).thenReturn(OrderStatus.Open)

        pending.expire(at(11), runLocked)
        pending.expire(at(11 + 121), runLocked)

        assertTrue(pending.isWaiting(key))
        assertEquals(2, cancelAttempts())

        pending.expire(at(10 * 60 + 1), runLocked)
        assertFalse(pending.isWaiting(key))
    }
}

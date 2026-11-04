package com.quantlog.trading

import com.quantlog.broker.BrokerClient
import com.quantlog.broker.CancelRequest
import com.quantlog.broker.Market
import com.quantlog.broker.OrderReceipt
import com.quantlog.broker.OrderRequest
import com.quantlog.broker.Side
import com.quantlog.position.FillProgress
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

/** 체결 확인 대기·미체결 취소 ([PendingOrders]). 대기는 주문이 끝났다고 확인될 때만 풀린다 — 시간이 지났다고 풀지 않는다. */
class PendingOrdersTest {
    private val broker = Mockito.mock(BrokerClient::class.java)
    private val tradeService = Mockito.mock(TradeService::class.java)
    private val holdingSync = Mockito.mock(FillProgress::class.java)
    private val checks = mutableListOf<String>()
    private val pending =
        PendingOrders("마틴게일", broker, tradeService, holdingSync, Duration.ofSeconds(10)) { checks += it }

    private val key = "KR:226490"
    private val orderNo = "0000011848"
    private val request = OrderRequest(Market.KR, "226490", Side.BUY, 44, BigDecimal("69595"))
    private val placedAt = Instant.parse("2026-10-08T00:44:42Z")

    private fun at(secondsAfterOrder: Long) = ZonedDateTime.ofInstant(placedAt.plusSeconds(secondsAfterOrder), ZoneId.of("Asia/Seoul"))

    /** 다음 날 09:00 KST. */
    private val nextDay = ZonedDateTime.of(2026, 10, 9, 9, 0, 0, 0, ZoneId.of("Asia/Seoul"))

    private val runLocked: (String, () -> Unit) -> Unit = { _, action -> action() }

    private fun <T> anyNonNull(): T = Mockito.any<T>()

    private fun track() = pending.track(key, request, OrderReceipt(orderNo, "ok", "00950"), placedAt)

    private fun cancelAttempts() = Mockito.mockingDetails(broker).invocations.count { it.method.name == "cancelOrder" }

    private fun closed(value: Boolean) = Mockito.`when`(holdingSync.isOrderClosed(orderNo)).thenReturn(value)

    @Test
    fun `주문이 끝났다고 확인되면 대기를 푼다`() {
        track()
        assertTrue(pending.isWaiting(key, at(0)))

        closed(true)
        assertFalse(pending.isWaiting(key, at(0)))
    }

    @Test
    fun `10초가 지나도 안 끝났으면 취소하고, 취소가 성공해도 체결조회로 끝난 것을 확인할 때까지 대기한다`() {
        track()

        pending.expire(at(11), runLocked)

        assertEquals(1, cancelAttempts())
        assertTrue(pending.isWaiting(key, at(0)))
        assertEquals(listOf("마틴게일 취소한 주문 $orderNo 확인"), checks)

        // 취소된 주문은 더 취소하지 않는다.
        pending.expire(at(30), runLocked)
        assertEquals(1, cancelAttempts())

        closed(true)
        assertFalse(pending.isWaiting(key, at(0)))
    }

    @Test
    fun `취소가 거부되면 체결조회를 요청하고 5초마다 다시 취소하며 시간이 아무리 지나도 대기를 풀지 않는다`() {
        track()
        Mockito.doThrow(IllegalStateException("정정/취소할 수량이 없습니다")).`when`(broker).cancelOrder(anyNonNull<CancelRequest>())

        pending.expire(at(11), runLocked)
        pending.expire(at(13), runLocked)
        pending.expire(at(17), runLocked)
        pending.expire(at(60 * 60), runLocked)

        assertEquals(3, cancelAttempts())
        assertEquals(3, checks.size)
        assertTrue(pending.isWaiting(key, at(0)))

        // 체결조회가 전량 체결을 반영하면 풀린다.
        closed(true)
        pending.expire(at(60 * 60 + 1), runLocked)
        assertFalse(pending.isWaiting(key, at(0)))
    }

    @Test
    fun `종목 잠금을 못 잡으면 취소를 미루고 대기는 그대로다`() {
        track()
        val neverLocked: (String, () -> Unit) -> Unit = { _, _ -> }

        pending.expire(at(10 * 60 + 1), neverLocked)

        assertEquals(0, cancelAttempts())
        assertTrue(pending.isWaiting(key, at(0)))
    }

    @Test
    fun `끝남 확인을 못 받은 채 날이 바뀌면 장 마감으로 소멸한 주문이라 대기를 푼다`() {
        // 2026-10-28 233740: 일부 체결 후 취소된 주문이 끝남 확인을 못 받았고, 끝남 확인은 오늘 주문만 찾아서 다음 날부터 영영 안 풀렸다.
        track()
        pending.expire(at(11), runLocked)
        assertTrue(pending.isWaiting(key, at(0)))

        assertFalse(pending.isWaiting(key, nextDay))
    }

    @Test
    fun `날이 바뀌면 주기 점검도 전날 주문을 대기에서 뺀다`() {
        track()

        pending.expire(nextDay, runLocked)

        assertEquals(0, cancelAttempts())
        assertFalse(pending.isWaiting(key, at(0)))
    }

    @Test
    fun `청산처럼 취소 성공에서 푸는 대기는 취소가 성공하면 끝남 확인 없이 바로 풀린다`() {
        val exits =
            PendingOrders("청산", broker, tradeService, holdingSync, Duration.ofSeconds(10), releaseOnCancel = true) {}
        exits.track(key, request, OrderReceipt(orderNo, "ok", "00950"), placedAt)

        exits.expire(at(5), runLocked)
        assertTrue(exits.isWaiting(key, at(0)))

        exits.expire(at(11), runLocked)
        assertFalse(exits.isWaiting(key, at(0)))
    }

    @Test
    fun `취소 성공에서 푸는 대기라도 취소가 거부되면 끝남 확인 전까지 대기한다`() {
        val exits =
            PendingOrders("청산", broker, tradeService, holdingSync, Duration.ofSeconds(10), releaseOnCancel = true) {}
        exits.track(key, request, OrderReceipt(orderNo, "ok", "00950"), placedAt)
        Mockito.doThrow(IllegalStateException("정정/취소할 수량이 없습니다")).`when`(broker).cancelOrder(anyNonNull<CancelRequest>())

        exits.expire(at(11), runLocked)
        assertTrue(exits.isWaiting(key, at(0)))
    }

    @Test
    fun `다시 싣기는 이미 대기 중인 주문을 덮지 않는다`() {
        track()
        val other = OrderReceipt("0000099999", "ok", "00950")

        pending.restore(key, request, other, placedAt.minusSeconds(60))
        pending.expire(at(11), runLocked)

        // 원래 주문(orderNo)만 취소한다 — 다시 싣기가 대기를 다른 주문으로 바꾸지 않았다.
        val canceled =
            Mockito.mockingDetails(broker).invocations.filter {
                it.method.name == "cancelOrder"
            }.map { (it.arguments[0] as CancelRequest).orderNo }
        assertEquals(listOf(orderNo), canceled)
    }

    @Test
    fun `앱이 뜬 뒤 한 번만, 이 대기열이 낸 끝나지 않은 주문 중 종목마다 가장 최근 것을 다시 싣는다`() {
        fun trade(
            no: String,
            symbol: String,
            reason: String,
            secondsAgo: Long,
        ) = com.quantlog.position.Trade(
            Market.KR, symbol, Side.BUY, 1, BigDecimal("100"), no, "ok",
            reason = reason, executedAt = placedAt.minusSeconds(secondsAgo), branchNo = "00950",
        )
        val trades =
            listOf(
                trade("OLD", "226490", "봇", 60),
                trade("NEW", "226490", "봇", 30),
                trade("DONE", "005930", "봇", 30),
                trade("MANUAL", "000660", "수동", 30),
            )
        Mockito.`when`(holdingSync.isOrderClosed("DONE")).thenReturn(true)
        var reads = 0

        pending.restoreOnce({
            reads++
            trades
        }) { it.reason == "봇" }
        pending.restoreOnce({
            reads++
            trades
        }) { it.reason == "봇" }

        assertEquals(1, reads)
        assertTrue(pending.isWaiting(key, at(0)))
        assertFalse(pending.isWaiting("KR:005930", at(0)))
        assertFalse(pending.isWaiting("KR:000660", at(0)))
        pending.expire(at(11), runLocked)
        val canceled =
            Mockito.mockingDetails(broker).invocations.filter {
                it.method.name == "cancelOrder"
            }.map { (it.arguments[0] as CancelRequest).orderNo }
        assertEquals(listOf("NEW"), canceled)
    }
}

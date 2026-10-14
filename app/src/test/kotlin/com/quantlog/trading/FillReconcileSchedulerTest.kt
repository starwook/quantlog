package com.quantlog.trading

import com.quantlog.broker.Market
import com.quantlog.broker.OrderRequest
import com.quantlog.broker.Side
import com.quantlog.gatewayclient.KisFillNoticeLinkChanged
import com.quantlog.position.FillReconcileRequested
import com.quantlog.position.FillReconcileService
import com.quantlog.position.ReconcileResult
import com.quantlog.sync.SyncEventRecorder
import com.quantlog.sync.SyncEventRepository
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 한투 연결이 끊기면 모든 주문을 멈추고, 다시 붙으면 체결조회로 맞춘 뒤에 여는 부분([FillReconcileScheduler]·[TradingHalt]·[RiskGuard]). */
class FillReconcileSchedulerTest {
    private val service = Mockito.mock(FillReconcileService::class.java)
    private val halt = TradingHalt()
    private val scheduler = FillReconcileScheduler(service, halt, SyncEventRecorder(Mockito.mock(SyncEventRepository::class.java)))
    private val guard = RiskGuard(halt) { null }
    private val sell = OrderRequest(Market.KR, "233740", Side.SELL, 100, BigDecimal("6800"))

    private fun <T> anyNonNull(): T = Mockito.any<T>()

    private fun waitUntil(condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < until) Thread.sleep(10)
    }

    @Test
    fun `한투 연결이 끊기면 매도까지 막고 다시 붙어 체결조회가 끝나야 연다`() {
        Mockito.`when`(service.reconcile(anyNonNull(), Mockito.anyBoolean())).thenReturn(ReconcileResult(1, 1, 1))
        val t = Instant.parse("2026-10-14T02:00:02Z")

        scheduler.onKisLink(KisFillNoticeLinkChanged(false, t, t))
        assertTrue(halt.active)
        assertFailsWith<TradingHaltedException> { guard.check(sell) }

        scheduler.onKisLink(KisFillNoticeLinkChanged(true, t.plusMillis(300), t))
        waitUntil { !halt.active }
        assertFalse(halt.active)
        guard.check(sell)
    }

    @Test
    fun `체결조회가 실패하면 멈춘 채 다시 시도하고 성공해야 연다`() {
        val calls = AtomicInteger()
        Mockito.`when`(service.reconcile(anyNonNull(), Mockito.anyBoolean())).thenAnswer {
            if (calls.incrementAndGet() < 3) throw IllegalStateException("EGW00201 초당 거래건수 초과")
            ReconcileResult()
        }

        scheduler.onRequested(FillReconcileRequested("앱 시작", halting = true))
        assertTrue(halt.active)

        waitUntil { !halt.active }
        assertFalse(halt.active)
        assertTrue(calls.get() >= 3)
    }

    @Test
    fun `멈추지 않는 확인 요청은 실패해도 거래를 막지 않는다`() {
        Mockito.`when`(service.reconcile(anyNonNull(), Mockito.anyBoolean())).thenThrow(IllegalStateException("조회 실패"))

        scheduler.onRequested(FillReconcileRequested("취소 거부 주문 확인", halting = false))
        Thread.sleep(100)

        assertFalse(halt.active)
        guard.check(sell)
    }
}

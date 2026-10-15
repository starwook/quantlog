package com.quantlog.position

import com.quantlog.broker.BrokerClient
import com.quantlog.broker.Market
import com.quantlog.broker.OrderFillTotal
import com.quantlog.broker.Side
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** 끝나지 않은 오늘 주문만 한투 체결조회로 확인하는 부분([FillReconcileService]). 반영 자체는 [OrderFillReconcileTest]. */
class FillReconcileServiceTest {
    private val now = Instant.now()
    private val broker = Mockito.mock(BrokerClient::class.java)
    private val tradeRepository = Mockito.mock(TradeRepository::class.java)
    private val applier = Mockito.mock(HoldingFillApplier::class.java)
    private val tradeService = Mockito.mock(TradeService::class.java)
    private val fills = InMemoryOrderFillStates()
    private val balanceSync = Mockito.mock(HoldingBalanceSync::class.java)
    private val service =
        FillReconcileService(broker, tradeRepository, fills.states, applier, tradeService, HoldingWriteLock(), balanceSync)

    private fun trade(
        orderNo: String,
        executedAt: Instant = now,
    ) = Trade(Market.KR, "005930", Side.BUY, 10, BigDecimal("70000"), orderNo, "ok", executedAt = executedAt)

    private fun settledState(orderNo: String) =
        OrderFillState(OrderFills.today(), Market.KR, orderNo, "005930", Side.BUY).also {
            it.recordInquiry(10, BigDecimal("700000"), remainingZero = true, now = now)
            fills.repository.save(it)
        }

    private fun total(orderNo: String) = OrderFillTotal(orderNo, 10, BigDecimal("70000"), BigDecimal("700000"), 0)

    @Test
    fun `확인할 주문이 없으면 한투를 부르지 않는다`() {
        Mockito.`when`(tradeRepository.findAll()).thenReturn(listOf(trade("A1", now.minusSeconds(86_400 * 2))))

        assertEquals(ReconcileResult(), service.reconcile(now))

        Mockito.verifyNoInteractions(broker)
    }

    @Test
    fun `끝난 주문은 빼고 남은 주문만 주문번호로 하나씩 조회해 반영한다`() {
        settledState("A1")
        val open = trade("A2")
        Mockito.`when`(tradeRepository.findAll()).thenReturn(listOf(trade("A1"), open))
        Mockito.`when`(broker.orderFillTotal(Market.KR, "A2")).thenReturn(total("A2"))

        val result = service.reconcile(now)

        assertEquals(1, result.checked)
        verify(broker, never()).orderFillTotal(Market.KR, "A1")
        verify(applier).applyInquiry(open, total("A2"), now)
    }

    @Test
    fun `남은 주문이 많으면 오늘 전체를 한 번에 받는다`() {
        val trades = (1..4).map { trade("B$it") }
        Mockito.`when`(tradeRepository.findAll()).thenReturn(trades)
        Mockito.`when`(broker.todayOrderFills(Market.KR)).thenReturn(listOf(total("B1"), total("B3")))

        val result = service.reconcile(now)

        assertEquals(4, result.checked)
        verify(broker, never()).orderFillTotal(Mockito.any(Market::class.java) ?: Market.KR, anyString())
        verify(applier).applyInquiry(trades[0], total("B1"), now)
        verify(applier).applyInquiry(trades[2], total("B3"), now)
        verify(applier).rebuildToday(now)
        Mockito.verifyNoMoreInteractions(applier)
    }

    @Test
    fun `덜 체결된 채 끝난 주문은 매매 기록을 취소로 바꾼다`() {
        val partial = trade("C1")
        Mockito.`when`(tradeRepository.findAll()).thenReturn(listOf(partial))
        val ended = OrderFillTotal("C1", 3, BigDecimal("70000"), BigDecimal("210000"), 0)
        Mockito.`when`(broker.orderFillTotal(Market.KR, "C1")).thenReturn(ended)
        // 반영하는 쪽이 하는 일(끝남 표시)을 흉내 낸다.
        Mockito.`when`(applier.applyInquiry(partial, ended, now)).thenAnswer {
            OrderFillState(OrderFills.today(), Market.KR, "C1", "005930", Side.BUY).also {
                it.recordInquiry(3, BigDecimal("210000"), remainingZero = true, now = now)
                fills.repository.save(it)
            }
            "3주"
        }

        val result = service.reconcile(now)

        assertEquals(1, result.settled)
        verify(tradeService).markCanceled(partial)
    }

    @Test
    fun `조회가 실패하면 예외를 그대로 던진다`() {
        Mockito.`when`(tradeRepository.findAll()).thenReturn(listOf(trade("D1")))
        Mockito.`when`(broker.orderFillTotal(Market.KR, "D1")).thenThrow(IllegalStateException("EGW00201"))

        assertFailsWith<IllegalStateException> { service.reconcile(now) }
    }

    @Test
    fun `거래를 멈추고 맞출 때는 마지막에 증권사 잔고로 보유를 대조하고 그냥 확인할 때는 안 한다`() {
        val kis = listOf(com.quantlog.broker.Holding(Market.KR, "005930", "삼성전자", BigDecimal(10), BigDecimal("70000"), BigDecimal("70100")))
        Mockito.`when`(tradeRepository.findAll()).thenReturn(emptyList())
        Mockito.`when`(broker.holdingsForReconcile(Market.KR)).thenReturn(kis)

        service.reconcile(now)
        verify(balanceSync, never()).sync(Mockito.anyList() ?: kis, Mockito.any(Instant::class.java) ?: now)

        service.reconcile(now, confirmBalance = true)
        verify(balanceSync).sync(Mockito.eq(kis) ?: kis, Mockito.any(Instant::class.java) ?: now)
    }
}

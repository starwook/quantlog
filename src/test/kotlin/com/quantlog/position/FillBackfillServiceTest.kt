package com.quantlog.position

import com.quantlog.broker.BrokerClient
import com.quantlog.broker.Market
import com.quantlog.broker.OrderFillTotal
import com.quantlog.broker.Side
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 체결통보를 놓쳤을 때 KIS 체결내역으로 원장을 채우는 부분 ([FillBackfillService]). */
class FillBackfillServiceTest {
    private val broker = Mockito.mock(BrokerClient::class.java)
    private val tradeRepository = Mockito.mock(TradeRepository::class.java)
    private val tradeFillRepository = Mockito.mock(TradeFillRepository::class.java)
    private val service = FillBackfillService(broker, tradeRepository, tradeFillRepository)

    private val now = Instant.parse("2026-10-08T01:30:00Z")

    private fun buy(
        orderNo: String = "O1",
        quantity: Int = 44,
        executedAt: Instant = now.minusSeconds(600),
    ) = Trade(Market.KR, "226490", Side.BUY, quantity, BigDecimal("69595"), orderNo, "ok", executedAt = executedAt)

    private fun fill(
        quantity: Int,
        price: String,
        filledAt: Instant = now.minusSeconds(500),
        orderNo: String = "O1",
    ) = TradeFill(Market.KR, "226490", Side.BUY, orderNo, quantity, BigDecimal(price), null, filledAt)

    private fun given(
        trades: List<Trade>,
        fills: List<TradeFill>,
        totals: List<OrderFillTotal>,
    ) {
        Mockito.`when`(tradeRepository.findAll()).thenReturn(trades)
        Mockito.`when`(tradeFillRepository.findAll()).thenReturn(fills)
        Mockito.`when`(broker.todayOrderFills(Market.KR)).thenReturn(totals)
    }

    private fun savedFills(): List<TradeFill> {
        val captor = ArgumentCaptor.forClass(TradeFill::class.java)
        verify(tradeFillRepository, Mockito.atLeast(0)).save(captor.capture() ?: fill(0, "0"))
        return captor.allValues
    }

    @Test
    fun `체결통보로 못 받은 체결을 KIS 누적과의 차이만큼 보충 줄로 채운다`() {
        given(listOf(buy()), listOf(fill(3, "69590")), listOf(OrderFillTotal("O1", 44, BigDecimal("69590"))))

        val result = service.backfill(now)

        assertEquals(1, result.filledOrders)
        val saved = savedFills().single()
        assertEquals(41, saved.quantity)
        assertEquals(0, BigDecimal("69590").compareTo(saved.price))
        assertEquals(FillSource.REST_BACKFILL, saved.source)
        assertNull(saved.avgCostBefore)
        assertEquals(now, saved.filledAt)
    }

    @Test
    fun `보충 줄의 가격은 KIS 평균가에서 원장에 있던 몫을 빼서 구한다`() {
        given(listOf(buy(quantity = 30)), listOf(fill(10, "100")), listOf(OrderFillTotal("O1", 30, BigDecimal("110"))))

        service.backfill(now)

        // (110×30 − 100×10) / 20 = 115
        assertEquals(0, BigDecimal("115").compareTo(savedFills().single().price))
    }

    @Test
    fun `방금 체결통보가 온 주문은 통보가 오는 중일 수 있어 건너뛴다`() {
        given(
            listOf(buy()),
            listOf(fill(3, "69590", filledAt = now.minusSeconds(5))),
            listOf(OrderFillTotal("O1", 44, BigDecimal("69590"))),
        )

        val result = service.backfill(now)

        assertEquals(0, result.filledOrders)
        verify(tradeFillRepository, never()).save(Mockito.any(TradeFill::class.java) ?: fill(0, "0"))
    }

    @Test
    fun `이미 주문수량만큼 원장에 쌓인 주문뿐이면 KIS 를 부르지 않는다`() {
        given(listOf(buy(quantity = 5)), listOf(fill(5, "69590")), emptyList())

        val result = service.backfill(now)

        assertEquals(false, result.queriedKis)
        verify(broker, never()).todayOrderFills(Market.KR)
    }

    @Test
    fun `원장이 KIS 보다 많으면 고치지 않고 어긋난 주문으로 센다`() {
        given(listOf(buy(quantity = 10)), listOf(fill(8, "69590")), listOf(OrderFillTotal("O1", 5, BigDecimal("69590"))))

        val result = service.backfill(now)

        assertEquals(1, result.mismatchedOrders)
        assertEquals(0, result.filledOrders)
    }

    @Test
    fun `KIS 조회가 실패하면 아무것도 채우지 않는다`() {
        Mockito.`when`(tradeRepository.findAll()).thenReturn(listOf(buy()))
        Mockito.`when`(tradeFillRepository.findAll()).thenReturn(emptyList())
        Mockito.`when`(broker.todayOrderFills(Market.KR)).thenThrow(IllegalStateException("KIS 지연"))

        val result = service.backfill(now)

        assertEquals(0, result.filledOrders)
        verify(tradeFillRepository, never()).save(Mockito.any(TradeFill::class.java) ?: fill(0, "0"))
    }

    @Test
    fun `어제 주문은 보지 않는다`() {
        given(listOf(buy(executedAt = now.minusSeconds(86_400 * 2))), emptyList(), listOf(OrderFillTotal("O1", 44, BigDecimal("1"))))

        service.backfill(now)

        verify(broker, never()).todayOrderFills(Market.KR)
    }
}

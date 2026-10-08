package com.quantlog.position

import com.quantlog.broker.FillNotice
import com.quantlog.broker.Market
import com.quantlog.broker.OrderReceipt
import com.quantlog.broker.OrderRequest
import com.quantlog.broker.Side
import com.quantlog.notification.Notifier
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.springframework.context.ApplicationEventPublisher
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 원장(`kis_broker_fill`) 줄이 반영될 때 매매 기록을 따라 갱신하는 부분 ([TradeService.onFillApplied]·[TradeService.onOrderNotice]·[TradeService.record]). */
class TradeServiceFillTest {
    private val repository = Mockito.mock(TradeRepository::class.java)
    private val notifier = Mockito.mock(Notifier::class.java)
    private val published = mutableListOf<Any>()
    private val ledger = InMemoryBrokerFills()
    private val service = TradeService(repository, ledger, notifier, ApplicationEventPublisher { published += it })

    private val order = OrderRequest(Market.KR, "005930", Side.BUY, 1, BigDecimal("278500"))
    private val receipt = OrderReceipt("A1", "ok", "00950")

    private fun <T> anyNonNull(): T = Mockito.any<T>()

    private fun notice(
        fill: Boolean = true,
        quantity: Int = 1,
        price: String = "277500",
        orderQuantity: Int = 1,
        side: Side = Side.BUY,
    ): FillNotice = fillNotice(side, quantity, price, "A1", orderQuantity = orderQuantity, fill = fill)

    /** 실제 흐름처럼 원장에 먼저 쌓고, 체결이면 보유 반영 이벤트를, 접수면 접수 처리를 부른다. */
    private fun receive(
        notice: FillNotice,
        service: TradeService = this.service,
        avgCostBefore: BigDecimal? = null,
    ) {
        val id = ledger.record(notice)
        if (!notice.isFill) return service.onOrderNotice(notice)
        service.onFillApplied(
            FillAppliedEvent(
                Market.KR, notice.symbol, notice.side!!, notice.orderNo, notice.filledQuantity!!.toInt(), notice.filledPrice!!,
                avgCostBefore, Instant.EPOCH, id,
            ),
        )
    }

    private fun savedTrades(): MutableList<Trade> {
        val saved = mutableListOf<Trade>()
        Mockito.`when`(repository.save(anyNonNull<Trade>())).thenAnswer { invocation ->
            (invocation.arguments[0] as Trade).also { saved += it }
        }
        return saved
    }

    @Test
    fun `증권사 취소 통보가 오면 원주문 기록을 취소로 바꾼다`() {
        val trade = Trade(Market.KR, "005930", Side.BUY, 1, BigDecimal("278500"), "A1", "ok")
        Mockito.`when`(repository.findFirstByMarketAndOrderNo(Market.KR, "A1")).thenReturn(trade)
        val cancelNotice = notice(fill = false).copy(orderNo = "A2", originalOrderNo = "A1", acceptFlag = "2")

        service.onOrderNotice(cancelNotice)

        assertTrue(trade.canceled)
        verify(repository).save(trade)
    }

    @Test
    fun `취소 통보가 와도 원주문이 이미 취소로 기록돼 있으면 다시 저장하지 않는다`() {
        val trade = Trade(Market.KR, "005930", Side.BUY, 1, BigDecimal("278500"), "A1", "ok").also { it.cancel() }
        Mockito.`when`(repository.findFirstByMarketAndOrderNo(Market.KR, "A1")).thenReturn(trade)

        service.onOrderNotice(notice(fill = false).copy(orderNo = "A2", originalOrderNo = "A1", acceptFlag = "2"))

        verify(repository, never()).save(anyNonNull<Trade>())
    }

    @Test
    fun `통보가 주문 응답보다 먼저 왔으면 매매 기록을 저장할 때 체결가를 바로 채운다`() {
        val saved = savedTrades()
        Mockito.`when`(repository.findFirstByMarketAndOrderNo(Market.KR, "A1")).thenReturn(null)
        receive(notice())

        service.record(order, receipt, "마틴게일")

        assertEquals(0, BigDecimal("277500").compareTo(saved.single().filledPrice))
    }

    @Test
    fun `기록이 먼저 있고 체결이 나중에 반영되면 체결가를 채워 저장한다`() {
        val trade = Trade(Market.KR, "005930", Side.BUY, 1, BigDecimal("278500"), "A1", "ok")
        Mockito.`when`(repository.findFirstByMarketAndOrderNo(Market.KR, "A1")).thenReturn(trade)

        receive(notice())

        assertEquals(0, BigDecimal("277500").compareTo(trade.filledPrice))
        verify(repository).save(trade)
        assertTrue(published.any { it is TradeChangedEvent })
    }

    @Test
    fun `이미 체결가가 있는 기록은 덮어쓰지 않는다`() {
        val trade = Trade(Market.KR, "005930", Side.BUY, 1, BigDecimal("278500"), "A1", "ok", initialFilledPrice = BigDecimal("270000"))
        Mockito.`when`(repository.findFirstByMarketAndOrderNo(Market.KR, "A1")).thenReturn(trade)

        receive(notice())

        assertEquals(0, BigDecimal("270000").compareTo(trade.filledPrice))
        verify(repository, never()).save(anyNonNull<Trade>())
    }

    @Test
    fun `접수 통보는 체결가로 쓰지 않고 증권사가 받은 미체결로 표시한다`() {
        val saved = savedTrades()
        receive(notice(fill = false))

        service.record(order, receipt, "마틴게일")

        assertNull(saved.single().filledPrice)
        assertEquals(OrderState.OPEN, saved.single().state)
    }

    @Test
    fun `기록이 있는 주문에 접수 통보가 반영되면 미체결 확인으로 바꾼다`() {
        val trade = Trade(Market.KR, "005930", Side.BUY, 1, BigDecimal("278500"), "A1", "ok")
        Mockito.`when`(repository.findFirstByMarketAndOrderNo(Market.KR, "A1")).thenReturn(trade)
        assertEquals(OrderState.CHECKING, trade.state)

        receive(notice(fill = false))

        assertEquals(OrderState.OPEN, trade.state)
        assertTrue(published.any { it is TradeChangedEvent })
    }

    @Test
    fun `거부된 접수 통보는 미체결 확인으로 보지 않는다`() {
        val trade = Trade(Market.KR, "005930", Side.BUY, 1, BigDecimal("278500"), "A1", "ok")
        Mockito.`when`(repository.findFirstByMarketAndOrderNo(Market.KR, "A1")).thenReturn(trade)

        service.onOrderNotice(notice(fill = false).copy(refuseFlag = "1"))

        assertEquals(OrderState.CHECKING, trade.state)
    }

    @Test
    fun `부분체결은 체결 누적만큼만 올리고 주문수량이 다 차야 체결로 본다`() {
        val trade = Trade(Market.KR, "005930", Side.BUY, 44, BigDecimal("69595"), "A1", "ok")
        Mockito.`when`(repository.findFirstByMarketAndOrderNo(Market.KR, "A1")).thenReturn(trade)

        receive(notice(quantity = 3, price = "69590", orderQuantity = 44))

        assertEquals(OrderState.PARTIAL, trade.state)
        assertEquals(3, trade.filledQuantity)

        receive(notice(quantity = 41, price = "69590", orderQuantity = 44))

        assertEquals(OrderState.FILLED, trade.state)
        assertEquals(44, trade.filledQuantity)
    }

    @Test
    fun `부분체결 도중 앱이 재시작돼도 누적은 원장에서 이어진다`() {
        val trade = Trade(Market.KR, "005930", Side.BUY, 44, BigDecimal("69595"), "A1", "ok")
        Mockito.`when`(repository.findFirstByMarketAndOrderNo(Market.KR, "A1")).thenReturn(trade)
        receive(notice(quantity = 3, price = "69500", orderQuantity = 44))

        // 재시작 = 메모리가 없는 새 서비스. 원장(DB)만 그대로다.
        val restarted = TradeService(repository, ledger, notifier, ApplicationEventPublisher { })
        receive(notice(quantity = 41, price = "69600", orderQuantity = 44), restarted)

        assertEquals(OrderState.FILLED, trade.state)
        assertEquals(44, trade.filledQuantity)
        // 가중평균: (3×69500 + 41×69600) / 44
        assertEquals(0, BigDecimal("69593.181818").compareTo(trade.filledPrice))
    }

    @Test
    fun `통보가 주문 응답보다 먼저 와서 일부만 체결됐으면 저장할 때도 일부 체결로 둔다`() {
        val saved = savedTrades()
        Mockito.`when`(repository.findFirstByMarketAndOrderNo(Market.KR, "A1")).thenReturn(null)
        receive(notice(quantity = 3, orderQuantity = 10))

        service.record(OrderRequest(Market.KR, "005930", Side.BUY, 10, BigDecimal("278500")), receipt, "마틴게일")

        assertEquals(OrderState.PARTIAL, saved.single().state)
        assertEquals(3, saved.single().filledQuantity)
    }

    @Test
    fun `매도 체결은 체결 직전 평단을 매매 기록에 한 번만 적는다`() {
        val trade = Trade(Market.KR, "005930", Side.SELL, 10, BigDecimal("71000"), "A1", "ok")
        Mockito.`when`(repository.findFirstByMarketAndOrderNo(Market.KR, "A1")).thenReturn(trade)

        receive(notice(side = Side.SELL, quantity = 4, price = "71000", orderQuantity = 10), avgCostBefore = BigDecimal("70000"))
        receive(notice(side = Side.SELL, quantity = 6, price = "71000", orderQuantity = 10), avgCostBefore = BigDecimal("69000"))

        assertEquals(0, BigDecimal("70000").compareTo(trade.avgCostBefore))
    }

    @Test
    fun `매수 체결은 체결 직전 평단을 적지 않는다`() {
        val trade = Trade(Market.KR, "005930", Side.BUY, 1, BigDecimal("278500"), "A1", "ok")
        Mockito.`when`(repository.findFirstByMarketAndOrderNo(Market.KR, "A1")).thenReturn(trade)

        receive(notice(), avgCostBefore = BigDecimal("270000"))

        assertNull(trade.avgCostBefore)
    }

    @Test
    fun `매도 체결이 주문 응답보다 먼저 반영됐으면 저장할 때 체결 직전 평단을 채운다`() {
        val saved = savedTrades()
        Mockito.`when`(repository.findFirstByMarketAndOrderNo(Market.KR, "A1")).thenReturn(null)
        receive(notice(side = Side.SELL, quantity = 10, price = "71000", orderQuantity = 10), avgCostBefore = BigDecimal("70000"))

        service.record(OrderRequest(Market.KR, "005930", Side.SELL, 10, BigDecimal("71000")), receipt, "청산")

        assertEquals(0, BigDecimal("70000").compareTo(saved.single().avgCostBefore))
        assertEquals(OrderState.FILLED, saved.single().state)
    }

    @Test
    fun `앱이 아직 반영하지 않은 원장 줄로는 저장할 때 체결로 채우지 않는다`() {
        val saved = savedTrades()
        ledger.record(notice())
        ledger.projectedUpTo = 0

        service.record(order, receipt, "마틴게일")

        assertNull(saved.single().filledPrice)
        assertFalse(saved.single().openConfirmed)
    }

    @Test
    fun `부분체결 중인 주문을 REST 조회로 전량 체결이 확인되면 체결로 올린다`() {
        val trade =
            Trade(
                Market.KR, "005930", Side.BUY, 10,
                BigDecimal(
                    "278500",
                ),
                "A1", "ok", initialFilledPrice = BigDecimal("277500"), initialFilledQuantity = 3,
            )

        assertTrue(trade.apply(com.quantlog.broker.OrderStatus.Filled(BigDecimal("277400"))))

        assertEquals(OrderState.FILLED, trade.state)
        assertEquals(10, trade.filledQuantity)
    }
}

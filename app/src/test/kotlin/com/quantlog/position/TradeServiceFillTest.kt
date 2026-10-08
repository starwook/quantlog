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
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 체결통보로 매매 기록의 체결가를 채우는 부분 ([TradeService.onFillNotice]). */
class TradeServiceFillTest {
    private val repository = Mockito.mock(TradeRepository::class.java)
    private val notifier = Mockito.mock(Notifier::class.java)
    private val published = mutableListOf<Any>()
    private val ledger = InMemoryTradeFills()
    private val service = TradeService(repository, ledger.repository, notifier, ApplicationEventPublisher { published += it })

    private val order = OrderRequest(Market.KR, "005930", Side.BUY, 1, BigDecimal("278500"))
    private val receipt = OrderReceipt("A1", "ok", "00950")

    private fun <T> anyNonNull(): T = Mockito.any<T>()

    private fun notice(
        fill: Boolean = true,
        quantity: BigDecimal = BigDecimal.ONE,
        price: BigDecimal = BigDecimal("277500"),
        orderQuantity: BigDecimal = BigDecimal.ONE,
    ) = FillNotice(
        symbol = "005930",
        orderNo = "A1",
        originalOrderNo = "",
        sellBuyCode = "02",
        filledFlag = if (fill) "2" else "1",
        acceptFlag = if (fill) "2" else "1",
        refuseFlag = "0",
        filledQuantity = if (fill) quantity else null,
        filledPrice = if (fill) price else null,
        orderQuantity = orderQuantity,
        orderPrice = BigDecimal("278500"),
        time = "092344",
    )

    /** 실제 흐름처럼 통보를 원장에 먼저 적고(applyFill) 그 뒤에 onFillNotice 를 부른다. */
    private fun receive(notice: FillNotice) {
        if (notice.isFill) {
            ledger.rows +=
                TradeFill(
                    Market.KR, notice.symbol, Side.BUY, notice.orderNo,
                    notice.filledQuantity!!.toInt(), notice.filledPrice!!, null, java.time.Instant.now(),
                )
        }
        service.onFillNotice(notice)
    }

    private fun savedTrades(): MutableList<Trade> {
        val saved = mutableListOf<Trade>()
        Mockito.`when`(repository.save(anyNonNull<Trade>())).thenAnswer { invocation ->
            (invocation.arguments[0] as Trade).also { saved += it }
        }
        return saved
    }

    @Test
    fun `통보가 주문 응답보다 먼저 왔으면 매매 기록을 저장할 때 체결가를 바로 채운다`() {
        val saved = savedTrades()
        Mockito.`when`(repository.findFirstByMarketAndOrderNo(Market.KR, "A1")).thenReturn(null)
        receive(notice())

        service.record(order, receipt, "마틴게일")

        assertEquals(0, BigDecimal("277500").compareTo(saved.single().filledPrice))
        assertTrue(published.any { it is TradeFilledEvent })
    }

    @Test
    fun `기록이 먼저 있고 통보가 나중에 오면 체결가를 채워 저장하되 잔고 동기화 이벤트는 내지 않는다`() {
        val trade = Trade(Market.KR, "005930", Side.BUY, 1, BigDecimal("278500"), "A1", "ok")
        Mockito.`when`(repository.findFirstByMarketAndOrderNo(Market.KR, "A1")).thenReturn(trade)

        receive(notice())

        assertEquals(0, BigDecimal("277500").compareTo(trade.filledPrice))
        verify(repository).save(trade)
        assertTrue(published.none { it is TradeFilledEvent })
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
    fun `접수 통보는 체결가로 쓰지 않는다`() {
        val saved = savedTrades()
        receive(notice(fill = false))

        service.record(order, receipt, "마틴게일")

        assertNull(saved.single().filledPrice)
    }

    @Test
    fun `부분체결 통보는 체결 누적만큼만 올리고 주문수량이 다 차야 체결로 본다`() {
        val trade = Trade(Market.KR, "005930", Side.BUY, 44, BigDecimal("69595"), "A1", "ok")
        Mockito.`when`(repository.findFirstByMarketAndOrderNo(Market.KR, "A1")).thenReturn(trade)

        receive(notice(quantity = BigDecimal(3), price = BigDecimal("69590"), orderQuantity = BigDecimal(44)))

        assertEquals(OrderState.PARTIAL, trade.state)
        assertEquals(3, trade.filledQuantity)

        receive(notice(quantity = BigDecimal(41), price = BigDecimal("69590"), orderQuantity = BigDecimal(44)))

        assertEquals(OrderState.FILLED, trade.state)
        assertEquals(44, trade.filledQuantity)
    }

    @Test
    fun `부분체결 도중 앱이 재시작돼도 누적은 원장에서 이어진다`() {
        val trade = Trade(Market.KR, "005930", Side.BUY, 44, BigDecimal("69595"), "A1", "ok")
        Mockito.`when`(repository.findFirstByMarketAndOrderNo(Market.KR, "A1")).thenReturn(trade)
        receive(notice(quantity = BigDecimal(3), price = BigDecimal("69500"), orderQuantity = BigDecimal(44)))

        // 재시작 = 메모리가 없는 새 서비스. 원장(DB)만 그대로다.
        val restarted = TradeService(repository, ledger.repository, notifier, ApplicationEventPublisher { })
        val second = notice(quantity = BigDecimal(41), price = BigDecimal("69600"), orderQuantity = BigDecimal(44))
        ledger.rows += TradeFill(Market.KR, "005930", Side.BUY, "A1", 41, BigDecimal("69600"), null, java.time.Instant.now())
        restarted.onFillNotice(second)

        assertEquals(OrderState.FILLED, trade.state)
        assertEquals(44, trade.filledQuantity)
        // 가중평균: (3×69500 + 41×69600) / 44
        assertEquals(0, BigDecimal("69593.181818").compareTo(trade.filledPrice))
    }

    @Test
    fun `통보가 주문 응답보다 먼저 와서 일부만 체결됐으면 저장할 때도 일부 체결로 둔다`() {
        val saved = savedTrades()
        Mockito.`when`(repository.findFirstByMarketAndOrderNo(Market.KR, "A1")).thenReturn(null)
        receive(notice(quantity = BigDecimal(3), orderQuantity = BigDecimal(10)))

        service.record(OrderRequest(Market.KR, "005930", Side.BUY, 10, BigDecimal("278500")), receipt, "마틴게일")

        assertEquals(OrderState.PARTIAL, saved.single().state)
        assertEquals(3, saved.single().filledQuantity)
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

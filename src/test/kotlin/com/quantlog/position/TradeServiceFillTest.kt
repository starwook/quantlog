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
    private val service = TradeService(repository, notifier, ApplicationEventPublisher { published += it })

    private val order = OrderRequest(Market.KR, "005930", Side.BUY, 1, BigDecimal("278500"))
    private val receipt = OrderReceipt("A1", "ok", "00950")

    private fun <T> anyNonNull(): T = Mockito.any<T>()

    private fun notice(fill: Boolean = true) =
        FillNotice(
            symbol = "005930",
            orderNo = "A1",
            originalOrderNo = "",
            sellBuyCode = "02",
            filledFlag = if (fill) "2" else "1",
            acceptFlag = if (fill) "2" else "1",
            refuseFlag = "0",
            filledQuantity = if (fill) BigDecimal.ONE else null,
            filledPrice = if (fill) BigDecimal("277500") else null,
            orderQuantity = BigDecimal.ONE,
            orderPrice = BigDecimal("278500"),
            time = "092344",
        )

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
        service.onFillNotice(notice())

        service.record(order, receipt, "마틴게일")

        assertEquals(0, BigDecimal("277500").compareTo(saved.single().filledPrice))
        assertTrue(published.any { it is TradeFilledEvent })
    }

    @Test
    fun `기록이 먼저 있고 통보가 나중에 오면 체결가를 채워 저장하되 잔고 동기화 이벤트는 내지 않는다`() {
        val trade = Trade(Market.KR, "005930", Side.BUY, 1, BigDecimal("278500"), "A1", "ok")
        Mockito.`when`(repository.findFirstByMarketAndOrderNo(Market.KR, "A1")).thenReturn(trade)

        service.onFillNotice(notice())

        assertEquals(0, BigDecimal("277500").compareTo(trade.filledPrice))
        verify(repository).save(trade)
        assertTrue(published.none { it is TradeFilledEvent })
        assertTrue(published.any { it is TradeChangedEvent })
    }

    @Test
    fun `이미 체결가가 있는 기록은 덮어쓰지 않는다`() {
        val trade = Trade(Market.KR, "005930", Side.BUY, 1, BigDecimal("278500"), "A1", "ok", initialFilledPrice = BigDecimal("270000"))
        Mockito.`when`(repository.findFirstByMarketAndOrderNo(Market.KR, "A1")).thenReturn(trade)

        service.onFillNotice(notice())

        assertEquals(0, BigDecimal("270000").compareTo(trade.filledPrice))
        verify(repository, never()).save(anyNonNull<Trade>())
    }

    @Test
    fun `접수 통보는 체결가로 쓰지 않는다`() {
        val saved = savedTrades()
        service.onFillNotice(notice(fill = false))

        service.record(order, receipt, "마틴게일")

        assertNull(saved.single().filledPrice)
    }
}

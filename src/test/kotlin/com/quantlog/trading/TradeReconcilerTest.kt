package com.quantlog.trading

import com.quantlog.broker.BrokerClient
import com.quantlog.broker.BuyingPower
import com.quantlog.broker.Holding
import com.quantlog.broker.Market
import com.quantlog.broker.MinuteCandle
import com.quantlog.broker.OrderReceipt
import com.quantlog.broker.OrderRequest
import com.quantlog.broker.Quote
import com.quantlog.broker.Side
import com.quantlog.position.Trade
import com.quantlog.position.TradeFilledEvent
import com.quantlog.position.TradeRepository
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.context.ApplicationEventPublisher
import java.math.BigDecimal
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TradeReconcilerTest {
    private val noEvents = Mockito.mock(ApplicationEventPublisher::class.java)

    private class FakeBroker(private val price: BigDecimal?) : BrokerClient {
        override fun quote(
            market: Market,
            symbol: String,
        ) = Quote(BigDecimal.ONE, BigDecimal.ONE)

        override fun minuteCandles(
            market: Market,
            symbol: String,
            atTime: LocalTime,
        ) = emptyList<MinuteCandle>()

        override fun filledPrice(
            market: Market,
            orderNo: String,
        ) = price

        override fun buyingPower(
            market: Market,
            symbol: String,
            price: BigDecimal,
        ) = BuyingPower("KRW", BigDecimal.ZERO, BigDecimal.ZERO)

        override fun holdings(market: Market) = emptyList<Holding>()

        override fun placeOrder(order: OrderRequest) = OrderReceipt("1", "ok")
    }

    private fun pendingTrade() =
        Trade(
            market = Market.KR,
            symbol = "005930",
            side = Side.BUY,
            quantity = 1,
            orderPrice = BigDecimal("273000"),
            orderNo = "0000008307",
            message = "모의투자 매수주문이 완료 되었습니다.",
        )

    @Test
    fun `체결가를 확인하면 채워서 저장한다`() {
        val trade = pendingTrade()
        val repository = Mockito.mock(TradeRepository::class.java)
        Mockito.`when`(repository.findAllByFilledPriceIsNull()).thenReturn(listOf(trade))

        val events = Mockito.mock(ApplicationEventPublisher::class.java)

        TradeReconciler(FakeBroker(BigDecimal("272000")), repository, ReconcileProperties(enabled = true), events).reconcile()

        Mockito.verify(events).publishEvent(TradeFilledEvent(trade.market, trade.symbol))

        assertEquals(0, BigDecimal("272000").compareTo(trade.filledPrice))
        Mockito.verify(repository).save(trade)
    }

    @Test
    fun `아직 체결 확인 안 되면 그대로 두고 저장하지 않는다`() {
        val trade = pendingTrade()
        val repository = Mockito.mock(TradeRepository::class.java)
        Mockito.`when`(repository.findAllByFilledPriceIsNull()).thenReturn(listOf(trade))

        TradeReconciler(
            FakeBroker(null),
            repository,
            ReconcileProperties(enabled = true),
            Mockito.mock(ApplicationEventPublisher::class.java),
        ).reconcile()

        assertNull(trade.filledPrice)
        Mockito.verify(repository, Mockito.never()).save(Mockito.any())
    }

    @Test
    fun `실패해도 다른 건 처리를 멈추지 않는다`() {
        val trade = pendingTrade()
        val repository = Mockito.mock(TradeRepository::class.java)
        Mockito.`when`(repository.findAllByFilledPriceIsNull()).thenReturn(listOf(trade))
        val broker =
            object : BrokerClient by FakeBroker(null) {
                override fun filledPrice(
                    market: Market,
                    orderNo: String,
                ): BigDecimal = throw RuntimeException("KIS 오류")
            }

        // 예외를 던져도 reconcile() 자체는 끝까지 실행된다 (다른 종목 처리를 막지 않음).
        TradeReconciler(
            broker,
            repository,
            ReconcileProperties(enabled = true),
            Mockito.mock(ApplicationEventPublisher::class.java),
        ).reconcile()
        assertNull(trade.filledPrice)
    }
}

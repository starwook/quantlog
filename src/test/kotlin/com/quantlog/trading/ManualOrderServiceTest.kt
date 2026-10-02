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
import com.quantlog.position.HoldingView
import com.quantlog.position.PortfolioService
import com.quantlog.position.PortfolioSnapshot
import com.quantlog.position.PortfolioSummary
import com.quantlog.position.TradeService
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ManualOrderServiceTest {
    private val krOpen = ZonedDateTime.of(2026, 9, 29, 10, 0, 0, 0, ZoneId.of("Asia/Seoul"))

    private class FakeBroker : BrokerClient {
        val orders = mutableListOf<OrderRequest>()

        override fun quote(
            market: Market,
            symbol: String,
        ) = Quote(BigDecimal("273000"), BigDecimal("500"))

        override fun minuteCandles(
            market: Market,
            symbol: String,
            atTime: LocalTime,
        ) = emptyList<MinuteCandle>()

        override fun filledPrice(
            market: Market,
            orderNo: String,
        ): BigDecimal? = null

        override fun buyingPower(
            market: Market,
            symbol: String,
            price: BigDecimal,
        ) = BuyingPower("KRW", BigDecimal.ZERO, BigDecimal.ZERO)

        override fun holdings(market: Market): List<Holding> = emptyList()

        override fun placeOrder(order: OrderRequest): OrderReceipt {
            orders += order
            return OrderReceipt("1", "ok")
        }
    }

    private val broker = FakeBroker()
    private val tradeService = Mockito.mock(TradeService::class.java)
    private val portfolioService = Mockito.mock(PortfolioService::class.java)
    private val service = ManualOrderService(broker, RiskGuard(RiskProperties(), portfolioService), tradeService, portfolioService)

    private fun holding(quantity: Int) {
        val price = BigDecimal("272000")
        val zero = BigDecimal.ZERO
        val holding = HoldingView(Market.KR, "005930", quantity, price, price, zero, zero, zero)
        val summary = PortfolioSummary("KRW", listOf(holding), zero, zero, zero, zero, zero, zero, zero, zero)
        Mockito.`when`(portfolioService.snapshot()).thenReturn(PortfolioSnapshot(emptyList(), emptyMap(), mapOf("KRW" to summary)))
    }

    @Test
    fun `매도는 현재가보다 한 호가 낮은 지정가로 낸다`() {
        holding(3)
        service.sell(Market.KR, "005930", 3, krOpen)
        val order = broker.orders.single()
        assertEquals(Side.SELL, order.side)
        assertEquals(3, order.quantity)
        assertEquals(0, BigDecimal("272500").compareTo(order.limitPrice))
    }

    @Test
    fun `보유 중이 아니면 주문을 내지 않는다`() {
        holding(0)
        assertFailsWith<IllegalStateException> { service.sell(Market.KR, "005930", 1, krOpen) }
        assertTrue(broker.orders.isEmpty())
    }

    @Test
    fun `장 시간이 아니면 주문을 내지 않는다`() {
        holding(1)
        val error = assertFailsWith<IllegalStateException> { service.sell(Market.KR, "005930", 1, krOpen.withHour(18)) }
        assertTrue(error.message!!.contains("거래 시간"))
        assertTrue(broker.orders.isEmpty())
    }

    @Test
    fun `보유 수량보다 많이는 팔 수 없다`() {
        holding(2)
        assertFailsWith<IllegalStateException> { service.sell(Market.KR, "005930", 3, krOpen) }
        assertTrue(broker.orders.isEmpty())
    }

    @Test
    fun `매수는 현재가보다 한 호가 높은 지정가로 낸다`() {
        Mockito.`when`(portfolioService.snapshot()).thenReturn(PortfolioSnapshot(emptyList(), emptyMap(), emptyMap()))
        service.buy(Market.KR, "005930", 2, krOpen)
        val order = broker.orders.single()
        assertEquals(Side.BUY, order.side)
        assertEquals(2, order.quantity)
        assertEquals(0, BigDecimal("273500").compareTo(order.limitPrice))
    }

    @Test
    fun `매수도 장 시간이 아니면 주문을 내지 않는다`() {
        assertFailsWith<IllegalStateException> { service.buy(Market.KR, "005930", 1, krOpen.withHour(18)) }
        assertTrue(broker.orders.isEmpty())
    }
}

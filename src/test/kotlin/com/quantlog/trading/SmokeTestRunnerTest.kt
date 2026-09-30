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
import com.quantlog.marketdata.MarketDataService
import com.quantlog.position.PortfolioService
import com.quantlog.position.PortfolioSnapshot
import com.quantlog.position.TradeService
import com.quantlog.strategy.FixedPercentExitRule
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.boot.DefaultApplicationArguments
import java.math.BigDecimal
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SmokeTestRunnerTest {
    private class FakeBroker : BrokerClient {
        val orders = mutableListOf<OrderRequest>()

        override fun quote(
            market: Market,
            symbol: String,
        ) = Quote(BigDecimal("200.00"), BigDecimal("0.01"))

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
        ) = BuyingPower("USD", BigDecimal("10000"), BigDecimal("50"))

        override fun holdings(market: Market) = emptyList<Holding>()

        override fun placeOrder(order: OrderRequest): OrderReceipt {
            orders += order
            return OrderReceipt("1", "ok")
        }
    }

    private val portfolioService =
        Mockito.mock(PortfolioService::class.java).also {
            Mockito.`when`(it.snapshot()).thenReturn(PortfolioSnapshot(emptyList(), emptyMap(), emptyMap()))
        }
    private val guard = RiskGuard(RiskProperties(), portfolioService)
    private val exitRule = FixedPercentExitRule(BigDecimal.ONE, BigDecimal.ONE)
    private val tradeService = Mockito.mock(TradeService::class.java)
    private val marketDataService = Mockito.mock(MarketDataService::class.java)

    private fun runner(
        broker: FakeBroker,
        properties: SmokeProperties,
        riskGuard: RiskGuard = guard,
    ) = SmokeTestRunner(broker, riskGuard, properties, exitRule, tradeService, marketDataService)

    @Test
    fun `mode 가 비어 있으면 주문하지 않는다`() {
        val broker = FakeBroker()
        runner(broker, SmokeProperties()).run(DefaultApplicationArguments())
        assertTrue(broker.orders.isEmpty())
    }

    @Test
    fun `READ 모드는 조회만 한다`() {
        val broker = FakeBroker()
        runner(broker, SmokeProperties(mode = "READ")).run(DefaultApplicationArguments())
        assertTrue(broker.orders.isEmpty())
    }

    @Test
    fun `BUY 모드는 현재가보다 약간 높은 지정가로 1주 주문한다`() {
        val broker = FakeBroker()
        runner(broker, SmokeProperties(mode = "BUY")).run(DefaultApplicationArguments())
        val order = broker.orders.single()
        assertEquals(Side.BUY, order.side)
        assertEquals(1, order.quantity)
        assertEquals(0, BigDecimal("201.00").compareTo(order.limitPrice))
    }

    @Test
    fun `배분 한도를 넘으면 주문이 나가지 않는다`() {
        val broker = FakeBroker()
        val strict = RiskGuard(RiskProperties(marketAllocationUsd = BigDecimal("100")), portfolioService)
        runCatching { runner(broker, SmokeProperties(mode = "BUY"), strict).run(DefaultApplicationArguments()) }
        assertTrue(broker.orders.isEmpty())
    }

    @Test
    fun `CANDLES 모드는 주문 없이 분봉만 받아 저장을 위임한다`() {
        val broker = FakeBroker()
        Mockito.`when`(marketDataService.fetchAndStoreRecentMinutes(anyNonNull(), anyNonNull(), anyNonNull()))
            .thenReturn(emptyList())
        runner(broker, SmokeProperties(mode = "CANDLES")).run(DefaultApplicationArguments())
        assertTrue(broker.orders.isEmpty())
        Mockito.verify(marketDataService).fetchAndStoreRecentMinutes(eqNonNull(Market.NASDAQ), eqNonNull("AAPL"), anyNonNull())
    }

    /** Mockito.any()/eq() 는 코틀린 non-null 타입 파라미터에 null 을 넘겨 NPE 를 낸다. */
    private fun <T> anyNonNull(): T = Mockito.any<T>()

    private fun <T> eqNonNull(value: T): T = Mockito.eq(value)
}

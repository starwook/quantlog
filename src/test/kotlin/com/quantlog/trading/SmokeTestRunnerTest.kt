package com.quantlog.trading

import com.quantlog.broker.BrokerClient
import com.quantlog.broker.BuyingPower
import com.quantlog.broker.Holding
import com.quantlog.broker.Market
import com.quantlog.broker.OrderReceipt
import com.quantlog.broker.OrderRequest
import com.quantlog.broker.Side
import com.quantlog.position.TradeService
import com.quantlog.strategy.FixedPercentExitRule
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.boot.DefaultApplicationArguments
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SmokeTestRunnerTest {
    private class FakeBroker : BrokerClient {
        val orders = mutableListOf<OrderRequest>()

        override fun currentPrice(
            market: Market,
            symbol: String,
        ) = BigDecimal("200.00")

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

    private val guard = RiskGuard(RiskProperties())
    private val exitRule = FixedPercentExitRule(BigDecimal.ONE, BigDecimal.ONE)
    private val tradeService = Mockito.mock(TradeService::class.java)

    @Test
    fun `mode 가 비어 있으면 주문하지 않는다`() {
        val broker = FakeBroker()
        SmokeTestRunner(broker, guard, SmokeProperties(), exitRule, tradeService).run(DefaultApplicationArguments())
        assertTrue(broker.orders.isEmpty())
    }

    @Test
    fun `READ 모드는 조회만 한다`() {
        val broker = FakeBroker()
        SmokeTestRunner(broker, guard, SmokeProperties(mode = "READ"), exitRule, tradeService)
            .run(DefaultApplicationArguments())
        assertTrue(broker.orders.isEmpty())
    }

    @Test
    fun `BUY 모드는 현재가보다 약간 높은 지정가로 1주 주문한다`() {
        val broker = FakeBroker()
        SmokeTestRunner(broker, guard, SmokeProperties(mode = "BUY"), exitRule, tradeService)
            .run(DefaultApplicationArguments())
        val order = broker.orders.single()
        assertEquals(Side.BUY, order.side)
        assertEquals(1, order.quantity)
        assertEquals(0, BigDecimal("201.00").compareTo(order.limitPrice))
    }

    @Test
    fun `가드 한도를 넘으면 주문이 나가지 않는다`() {
        val broker = FakeBroker()
        val strict = RiskGuard(RiskProperties(maxOrderUsd = BigDecimal("100")))
        runCatching {
            SmokeTestRunner(broker, strict, SmokeProperties(mode = "BUY"), exitRule, tradeService)
                .run(DefaultApplicationArguments())
        }
        assertTrue(broker.orders.isEmpty())
    }
}

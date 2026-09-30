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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HoldingSyncServiceTest {
    private val symbol = "005930"

    private class FakeBroker(private val kis: List<Holding>) : BrokerClient {
        override fun quote(
            market: Market,
            symbol: String,
        ) = Quote(BigDecimal("9000"), BigDecimal("5"))

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

        override fun holdings(market: Market): List<Holding> = if (market == Market.KR) kis else emptyList()

        override fun placeOrder(order: OrderRequest) = OrderReceipt("1", "ok")
    }

    private fun kis(
        quantity: Int,
        average: String,
    ) = Holding(Market.KR, symbol, "KODEX", BigDecimal(quantity), BigDecimal(average), BigDecimal("9000"))

    private fun portfolioWith(
        quantity: Int,
        average: String,
    ): PortfolioService {
        val zero = BigDecimal.ZERO
        val holdings =
            if (quantity == 0) {
                emptyList()
            } else {
                listOf(HoldingView(Market.KR, symbol, quantity, BigDecimal(average), BigDecimal(average), false, zero, zero, zero))
            }
        val summary = PortfolioSummary("KRW", holdings, zero, zero, zero, zero, zero, zero, zero, zero)
        val service = Mockito.mock(PortfolioService::class.java)
        Mockito.`when`(service.snapshot()).thenReturn(PortfolioSnapshot(emptyList(), emptyMap(), mapOf("KRW" to summary)))
        return service
    }

    private fun recordedOrders(trades: TradeService) =
        Mockito.mockingDetails(trades).invocations
            .filter { it.method.name == "record" }
            .map { it.arguments[0] as OrderRequest }

    @Test
    fun `봇이 모르는 직접 매수는 KIS 평단이 맞도록 BUY 로 반영한다`() {
        val trades = Mockito.mock(TradeService::class.java)
        // 봇은 2주(평단 9,000)를 안다. KIS 는 5주 평단 8,900 → 총매입 44,500 − 18,000 = 26,500 / 3주 ≈ 8,833.3333
        val messages = HoldingSyncService(FakeBroker(listOf(kis(5, "8900"))), trades, portfolioWith(2, "9000")).sync()

        val order = recordedOrders(trades).single()
        assertEquals(Side.BUY, order.side)
        assertEquals(3, order.quantity)
        assertEquals(0, BigDecimal("8833.3333").compareTo(order.limitPrice))
        assertEquals(1, messages.size)
    }

    @Test
    fun `봇 기록이 비어 있으면 KIS 평단 그대로 전량 BUY`() {
        val trades = Mockito.mock(TradeService::class.java)
        HoldingSyncService(FakeBroker(listOf(kis(4, "9100"))), trades, portfolioWith(0, "0")).sync()

        val order = recordedOrders(trades).single()
        assertEquals(4, order.quantity)
        assertEquals(0, BigDecimal("9100").compareTo(order.limitPrice))
    }

    @Test
    fun `앱에서 판 물량은 현재가로 추정해 SELL 로 반영한다`() {
        val trades = Mockito.mock(TradeService::class.java)
        HoldingSyncService(FakeBroker(emptyList()), trades, portfolioWith(3, "9000")).sync()

        val order = recordedOrders(trades).single()
        assertEquals(Side.SELL, order.side)
        assertEquals(3, order.quantity)
        assertEquals(0, BigDecimal("9000").compareTo(order.limitPrice))
    }

    @Test
    fun `이미 같으면 아무것도 기록하지 않는다`() {
        val trades = Mockito.mock(TradeService::class.java)
        val messages = HoldingSyncService(FakeBroker(listOf(kis(2, "9000"))), trades, portfolioWith(2, "9000")).sync()

        assertTrue(recordedOrders(trades).isEmpty())
        assertTrue(messages.isEmpty())
    }
}

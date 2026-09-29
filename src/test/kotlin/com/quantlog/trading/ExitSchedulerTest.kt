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
import com.quantlog.strategy.FixedPercentExitRule
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExitSchedulerTest {
    /** 삼성전자 1주, 평단 272,000원 → 익절 274,500 / 손절 269,500 (호가 500원). */
    private class FakeBroker(var price: BigDecimal) : BrokerClient {
        val orders = mutableListOf<OrderRequest>()

        override fun quote(
            market: Market,
            symbol: String,
        ) = Quote(price, BigDecimal("500"))

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

    private val seoul = ZoneId.of("Asia/Seoul")

    /** 2026-09-29(화) 10:00 KST: 국내 정규장, 미국은 장 마감 시간. */
    private val krOpen = ZonedDateTime.of(2026, 9, 29, 10, 0, 0, 0, seoul)

    /** ExitScheduler 는 이제 보유 현황을 REST 대신 PortfolioService(DB)로 본다. */
    private fun portfolioServiceWithHolding(): PortfolioService {
        val price = BigDecimal("272000")
        val zero = BigDecimal.ZERO
        val holding = HoldingView(Market.KR, "005930", 1, price, price, false, zero, zero, zero)
        val summary =
            PortfolioSummary(
                "KRW", listOf(holding), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            )
        val service = Mockito.mock(PortfolioService::class.java)
        Mockito.`when`(service.snapshot()).thenReturn(PortfolioSnapshot(emptyList(), emptyMap(), mapOf("KRW" to summary)))
        return service
    }

    private fun scheduler(broker: FakeBroker) =
        ExitScheduler(
            broker,
            RiskGuard(RiskProperties(), Mockito.mock(PortfolioService::class.java)),
            FixedPercentExitRule(BigDecimal.ONE, BigDecimal.ONE),
            Mockito.mock(TradeService::class.java),
            portfolioServiceWithHolding(),
            ExitProperties(enabled = true),
        )

    @Test
    fun `익절 목표가에 닿으면 현재가보다 한 호가 낮게 전량 매도한다`() {
        val broker = FakeBroker(BigDecimal("274500"))
        scheduler(broker).checkExits(krOpen)

        val order = broker.orders.single()
        assertEquals(Side.SELL, order.side)
        assertEquals(1, order.quantity)
        assertEquals(0, BigDecimal("274000").compareTo(order.limitPrice))
    }

    @Test
    fun `손절 목표가에 닿아도 매도한다`() {
        val broker = FakeBroker(BigDecimal("269500"))
        scheduler(broker).checkExits(krOpen)
        assertEquals(Side.SELL, broker.orders.single().side)
    }

    @Test
    fun `목표가 사이면 팔지 않는다`() {
        val broker = FakeBroker(BigDecimal("272500"))
        scheduler(broker).checkExits(krOpen)
        assertTrue(broker.orders.isEmpty())
    }

    @Test
    fun `한 번 판 종목은 대기 시간 동안 다시 팔지 않는다`() {
        val broker = FakeBroker(BigDecimal("274500"))
        val scheduler = scheduler(broker)
        scheduler.checkExits(krOpen)
        scheduler.checkExits(krOpen.plusMinutes(1))
        assertEquals(1, broker.orders.size)

        scheduler.checkExits(krOpen.plusMinutes(11))
        assertEquals(2, broker.orders.size)
    }

    @Test
    fun `장 시간이 아니면 아무것도 안 한다`() {
        val broker = FakeBroker(BigDecimal("274500"))
        scheduler(broker).checkExits(krOpen.withHour(16))
        assertTrue(broker.orders.isEmpty())
    }
}

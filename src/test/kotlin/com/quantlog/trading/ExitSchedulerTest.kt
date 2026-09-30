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
import com.quantlog.position.HoldingSyncService
import com.quantlog.position.HoldingView
import com.quantlog.position.PortfolioService
import com.quantlog.position.PortfolioSnapshot
import com.quantlog.position.PortfolioSummary
import com.quantlog.position.Trade
import com.quantlog.position.TradeService
import com.quantlog.strategy.FixedPercentExitRule
import com.quantlog.strategy.MartingaleCycle
import com.quantlog.watchlist.SymbolStrategyService
import com.quantlog.watchlist.symbolStrategy
import com.quantlog.watchlist.symbolStrategyServiceOf
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
    private fun portfolioServiceWithHolding(
        quantity: Int = 1,
        price: BigDecimal = BigDecimal("272000"),
    ): PortfolioService {
        val zero = BigDecimal.ZERO
        val holding = HoldingView(Market.KR, "005930", quantity, price, price, zero, zero, zero)
        val summary =
            PortfolioSummary(
                "KRW", listOf(holding), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            )
        val service = Mockito.mock(PortfolioService::class.java)
        Mockito.`when`(service.snapshot()).thenReturn(PortfolioSnapshot(emptyList(), emptyMap(), mapOf("KRW" to summary)))
        return service
    }

    private fun scheduler(
        broker: FakeBroker,
        tradeService: TradeService = Mockito.mock(TradeService::class.java),
    ) = ExitScheduler(
        broker,
        RiskGuard(RiskProperties(), Mockito.mock(PortfolioService::class.java)),
        FixedPercentExitRule(BigDecimal.ONE, BigDecimal.ONE),
        // 설정 행 없음 → 전역 익절/손절 규칙으로 폴백
        Mockito.mock(SymbolStrategyService::class.java),
        tradeService,
        portfolioServiceWithHolding(),
        Mockito.mock(HoldingSyncService::class.java),
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

    // ── 마틴게일(삼성전자) 손절: 전역 손절은 보류(null)이고, 5단계 매수 뒤에만 별도 손절이 있다 ──

    private fun martingaleScheduler(
        broker: FakeBroker,
        vararg buyPrices: Pair<Int, String>,
    ): ExitScheduler {
        val tradeService = Mockito.mock(TradeService::class.java)
        val trades =
            buyPrices.map { (qty, price) ->
                Trade(Market.KR, "005930", Side.BUY, qty, BigDecimal(price), "0", "ok", initialFilledPrice = BigDecimal(price))
            }
        Mockito.`when`(tradeService.trades(Market.KR, "005930")).thenReturn(trades)
        // 잔고 테이블은 사이클의 실제 보유 수량·평단과 같다고 본다.
        val cycle = MartingaleCycle.from(trades)
        return ExitScheduler(
            broker,
            RiskGuard(RiskProperties(), Mockito.mock(PortfolioService::class.java)),
            FixedPercentExitRule(BigDecimal("0.5"), null),
            symbolStrategyServiceOf(symbolStrategy(Market.KR, "005930", martingale = true)),
            tradeService,
            portfolioServiceWithHolding(cycle.quantity, cycle.averagePrice!!),
            Mockito.mock(HoldingSyncService::class.java),
            ExitProperties(enabled = true),
        )
    }

    private val fiveStages = arrayOf(1 to "272000", 2 to "270500", 4 to "269000", 8 to "267500", 16 to "266000")

    @Test
    fun `마틴게일 5단계 손절 경계 - 평단 -3퍼센트 이하면 전량 매도`() {
        // 평단 = 8,285,000 / 31 ≈ 267,258 → × 0.97 = 259,240 → 259,000
        val broker = FakeBroker(BigDecimal("259000"))
        martingaleScheduler(broker, *fiveStages).checkExits(krOpen)
        assertEquals(Side.SELL, broker.orders.single().side)
    }

    @Test
    fun `마틴게일 5단계라도 손절가 위면 팔지 않는다`() {
        val broker = FakeBroker(BigDecimal("259500"))
        martingaleScheduler(broker, *fiveStages).checkExits(krOpen)
        assertTrue(broker.orders.isEmpty())
    }

    @Test
    fun `마틴게일 1~4단계에서는 아무리 떨어져도 손절 안 한다`() {
        val broker = FakeBroker(BigDecimal("150000"))
        martingaleScheduler(broker, *fiveStages.take(4).toTypedArray()).checkExits(krOpen)
        assertTrue(broker.orders.isEmpty())
    }

    @Test
    fun `종목별 DB 설정의 익절 퍼센트가 전역 설정보다 우선한다`() {
        // 전역은 +1%(274,500). DB 에서 삼성전자만 +2%(277,500)로 바꾸면 274,500 에서 안 팔고 277,500 에서 판다.
        fun sellsAt(price: String): Boolean {
            val broker = FakeBroker(BigDecimal(price))
            ExitScheduler(
                broker,
                RiskGuard(RiskProperties(), Mockito.mock(PortfolioService::class.java)),
                FixedPercentExitRule(BigDecimal.ONE, BigDecimal.ONE),
                symbolStrategyServiceOf(symbolStrategy(Market.KR, "005930", takeProfit = "2")),
                Mockito.mock(TradeService::class.java),
                portfolioServiceWithHolding(),
                Mockito.mock(HoldingSyncService::class.java),
                ExitProperties(enabled = true),
            ).checkExits(krOpen)
            return broker.orders.isNotEmpty()
        }
        assertTrue(!sellsAt("274500"))
        assertTrue(sellsAt("277500"))
    }
}

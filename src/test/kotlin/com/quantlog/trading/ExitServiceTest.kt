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
import com.quantlog.position.AccountHolding
import com.quantlog.position.AccountHoldingRepository
import com.quantlog.position.HoldingSyncService
import com.quantlog.position.PortfolioService
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

class ExitServiceTest {
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

    /** ExitService 는 보유 현황을 잔고 사본 테이블(AccountHolding)로 본다. */
    private fun accountHoldingRepositoryWithHolding(
        quantity: Int = 1,
        price: BigDecimal = BigDecimal("272000"),
    ): AccountHoldingRepository {
        val holding = AccountHolding(Market.KR, "005930", quantity, price, price)
        val repository = Mockito.mock(AccountHoldingRepository::class.java)
        Mockito.`when`(repository.findAll()).thenReturn(listOf(holding))
        Mockito.`when`(repository.findByMarketAndSymbol(Market.KR, "005930")).thenReturn(holding)
        return repository
    }

    private fun scheduler(
        broker: FakeBroker,
        tradeService: TradeService = Mockito.mock(TradeService::class.java),
    ) = ExitService(
        broker,
        RiskGuard(RiskProperties(), Mockito.mock(PortfolioService::class.java)),
        FixedPercentExitRule(BigDecimal.ONE, BigDecimal.ONE),
        // 설정 행 없음 → 전역 익절/손절 규칙으로 폴백
        Mockito.mock(SymbolStrategyService::class.java),
        tradeService,
        accountHoldingRepositoryWithHolding(),
        Mockito.mock(HoldingSyncService::class.java),
        ExitProperties(enabled = true),
    )

    @Test
    fun `익절 목표가에 닿으면 현재가보다 한 호가 낮게 전량 매도한다`() {
        val broker = FakeBroker(BigDecimal("274500"))
        scheduler(broker).checkAll(krOpen)

        val order = broker.orders.single()
        assertEquals(Side.SELL, order.side)
        assertEquals(1, order.quantity)
        assertEquals(0, BigDecimal("274000").compareTo(order.limitPrice))
    }

    @Test
    fun `손절 목표가에 닿아도 매도한다`() {
        val broker = FakeBroker(BigDecimal("269500"))
        scheduler(broker).checkAll(krOpen)
        assertEquals(Side.SELL, broker.orders.single().side)
    }

    @Test
    fun `목표가 사이면 팔지 않는다`() {
        val broker = FakeBroker(BigDecimal("272500"))
        scheduler(broker).checkAll(krOpen)
        assertTrue(broker.orders.isEmpty())
    }

    @Test
    fun `한 번 판 종목은 대기 시간 동안 다시 팔지 않는다`() {
        val broker = FakeBroker(BigDecimal("274500"))
        val scheduler = scheduler(broker)
        scheduler.checkAll(krOpen)
        scheduler.checkAll(krOpen.plusMinutes(1))
        assertEquals(1, broker.orders.size)

        scheduler.checkAll(krOpen.plusMinutes(11))
        assertEquals(2, broker.orders.size)
    }

    @Test
    fun `장 시간이 아니면 아무것도 안 한다`() {
        val broker = FakeBroker(BigDecimal("274500"))
        scheduler(broker).checkAll(krOpen.withHour(16))
        assertTrue(broker.orders.isEmpty())
    }

    // ── 마틴게일(삼성전자) 손절: 전역 손절은 보류(null)이고, 5단계 매수 뒤에만 별도 손절이 있다 ──

    private fun martingaleScheduler(
        broker: FakeBroker,
        vararg buyPrices: Pair<Int, String>,
    ): ExitService {
        val tradeService = Mockito.mock(TradeService::class.java)
        val trades =
            buyPrices.map { (qty, price) ->
                Trade(Market.KR, "005930", Side.BUY, qty, BigDecimal(price), "0", "ok", initialFilledPrice = BigDecimal(price))
            }
        Mockito.`when`(tradeService.trades(Market.KR, "005930")).thenReturn(trades)
        // 잔고 테이블은 사이클의 실제 보유 수량·평단과 같다고 본다.
        val cycle = MartingaleCycle.from(trades)
        return ExitService(
            broker,
            RiskGuard(RiskProperties(), Mockito.mock(PortfolioService::class.java)),
            FixedPercentExitRule(BigDecimal("0.5"), null),
            symbolStrategyServiceOf(symbolStrategy(Market.KR, "005930", martingale = true)),
            tradeService,
            accountHoldingRepositoryWithHolding(cycle.quantity, cycle.averagePrice!!),
            Mockito.mock(HoldingSyncService::class.java),
            ExitProperties(enabled = true),
        )
    }

    private val fiveStages = arrayOf(1 to "272000", 2 to "270500", 4 to "269000", 8 to "267500", 16 to "266000")

    @Test
    fun `마틴게일 5단계 손절 경계 - 평단 -3퍼센트 이하면 전량 매도`() {
        // 평단 = 8,285,000 / 31 ≈ 267,258 → × 0.97 = 259,240 → 259,000
        val broker = FakeBroker(BigDecimal("259000"))
        martingaleScheduler(broker, *fiveStages).checkAll(krOpen)
        assertEquals(Side.SELL, broker.orders.single().side)
    }

    @Test
    fun `마틴게일 5단계라도 손절가 위면 팔지 않는다`() {
        val broker = FakeBroker(BigDecimal("259500"))
        martingaleScheduler(broker, *fiveStages).checkAll(krOpen)
        assertTrue(broker.orders.isEmpty())
    }

    @Test
    fun `마틴게일 1~4단계에서는 아무리 떨어져도 손절 안 한다`() {
        val broker = FakeBroker(BigDecimal("150000"))
        martingaleScheduler(broker, *fiveStages.take(4).toTypedArray()).checkAll(krOpen)
        assertTrue(broker.orders.isEmpty())
    }

    @Test
    fun `종목별 DB 설정의 익절 퍼센트가 전역 설정보다 우선한다`() {
        // 전역은 +1%(274,500). DB 에서 삼성전자만 +2%(277,500)로 바꾸면 274,500 에서 안 팔고 277,500 에서 판다.
        fun sellsAt(price: String): Boolean {
            val broker = FakeBroker(BigDecimal(price))
            ExitService(
                broker,
                RiskGuard(RiskProperties(), Mockito.mock(PortfolioService::class.java)),
                FixedPercentExitRule(BigDecimal.ONE, BigDecimal.ONE),
                symbolStrategyServiceOf(symbolStrategy(Market.KR, "005930", takeProfit = "2")),
                Mockito.mock(TradeService::class.java),
                accountHoldingRepositoryWithHolding(),
                Mockito.mock(HoldingSyncService::class.java),
                ExitProperties(enabled = true),
            ).checkAll(krOpen)
            return broker.orders.isNotEmpty()
        }
        assertTrue(!sellsAt("274500"))
        assertTrue(sellsAt("277500"))
    }

    @Test
    fun `종목 하나만 판정해도 목표가에 닿으면 매도한다`() {
        val broker = FakeBroker(BigDecimal("274500"))
        scheduler(broker).checkSymbol(Market.KR, "005930", krOpen)
        assertEquals(Side.SELL, broker.orders.single().side)
    }

    @Test
    fun `보유하지 않은 종목의 틱은 무시한다`() {
        val broker = FakeBroker(BigDecimal("274500"))
        val service = scheduler(broker)
        service.checkSymbol(Market.KR, "000660", krOpen)
        assertTrue(broker.orders.isEmpty())
    }

    @Test
    fun `실시간이 맡은 종목은 전체 폴링에서 건너뛴다`() {
        val broker = FakeBroker(BigDecimal("274500"))
        scheduler(broker).checkAll(krOpen) { _, symbol -> symbol == "005930" }
        assertTrue(broker.orders.isEmpty())
    }
}

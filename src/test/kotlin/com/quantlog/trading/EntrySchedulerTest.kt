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
import com.quantlog.position.HoldingSyncService
import com.quantlog.position.HoldingView
import com.quantlog.position.PortfolioService
import com.quantlog.position.PortfolioSnapshot
import com.quantlog.position.PortfolioSummary
import com.quantlog.position.Trade
import com.quantlog.position.TradeService
import com.quantlog.strategy.EntrySignal
import com.quantlog.strategy.MartingaleCycle
import com.quantlog.strategy.SupportBounceEntryRule
import com.quantlog.watchlist.SeedSymbol
import com.quantlog.watchlist.SymbolStrategy
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

class EntrySchedulerTest {
    private class FakeBroker : BrokerClient {
        val orders = mutableListOf<OrderRequest>()

        override fun quote(
            market: Market,
            symbol: String,
        ) = Quote(BigDecimal("10000"), BigDecimal("10"))

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

    /** 2026-09-29(화) 10:00 KST: 국내 정규장. */
    private val krOpen = ZonedDateTime.of(2026, 9, 29, 10, 0, 0, 0, seoul)

    /** Mockito.any()/eq() 는 코틀린 non-null 타입 파라미터에 null 을 넘겨 NPE 를 낸다. */
    private fun <T> anyNonNull(): T = Mockito.any<T>()

    private fun <T> eqNonNull(value: T): T = Mockito.eq(value)

    private fun entryRuleReturning(signal: EntrySignal): SupportBounceEntryRule {
        val rule = Mockito.mock(SupportBounceEntryRule::class.java)
        Mockito.`when`(rule.evaluate(anyNonNull())).thenReturn(signal)
        return rule
    }

    private fun marketDataServiceStub(): MarketDataService {
        val service = Mockito.mock(MarketDataService::class.java)
        Mockito.`when`(service.recentCandles(anyNonNull(), anyNonNull(), anyNonNull())).thenReturn(emptyList())
        return service
    }

    private fun noopPortfolioService(): PortfolioService {
        val service = Mockito.mock(PortfolioService::class.java)
        Mockito.`when`(service.snapshot()).thenReturn(PortfolioSnapshot(emptyList(), emptyMap(), emptyMap()))
        return service
    }

    /** EntryScheduler 는 이제 "이미 보유 중인지"를 REST 대신 PortfolioService(DB)로 본다. */
    private fun portfolioServiceHolding(symbol: String): PortfolioService {
        val zero = BigDecimal.ZERO
        val holding = HoldingView(Market.KR, symbol, 1, BigDecimal("9000"), BigDecimal("10000"), zero, zero, zero)
        val summary =
            PortfolioSummary(
                "KRW", listOf(holding), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            )
        val service = Mockito.mock(PortfolioService::class.java)
        Mockito.`when`(service.snapshot()).thenReturn(PortfolioSnapshot(emptyList(), emptyMap(), mapOf("KRW" to summary)))
        return service
    }

    /** 삼성전자는 마틴게일만(저점 판단 진입 끔), KODEX 는 저점 판단 진입, SK하이닉스는 매수 끔. */
    private val defaultConfigs =
        listOf(
            symbolStrategy(Market.KR, "005930", martingale = true, supportBounceEntry = false),
            symbolStrategy(Market.KR, "091160"),
            symbolStrategy(Market.KR, "000660", supportBounceEntry = false),
        )

    private fun scheduler(
        broker: FakeBroker,
        signal: EntrySignal,
        watched: List<SeedSymbol> = listOf(SeedSymbol.SAMSUNG),
        portfolioService: PortfolioService = noopPortfolioService(),
        marketDataService: MarketDataService = marketDataServiceStub(),
        tradeService: TradeService = Mockito.mock(TradeService::class.java),
        configs: List<SymbolStrategy> = defaultConfigs,
    ) = EntryScheduler(
        broker,
        RiskGuard(RiskProperties(), noopPortfolioService()),
        entryRuleReturning(signal),
        symbolStrategyServiceOf(*configs.toTypedArray()).also { service ->
            // 감시 종목 목록은 DB(symbol_strategy) 행 목록 — 이 테스트에서 감시 중인 종목의 설정만 돌려준다.
            Mockito.`when`(service.all()).thenReturn(configs.filter { c -> watched.any { it.market == c.market && it.symbol == c.symbol } })
        },
        marketDataService,
        tradeService,
        portfolioService,
        Mockito.mock(HoldingSyncService::class.java),
        EntryProperties(enabled = true),
    )

    @Test
    fun `매수 신호가 뜨면 소량 매수한다`() {
        val broker = FakeBroker()
        scheduler(broker, EntrySignal.BUY, watched = listOf(SeedSymbol.KODEX_SEMICONDUCTOR)).checkEntries(krOpen)

        val order = broker.orders.single()
        assertEquals(Side.BUY, order.side)
        assertEquals("091160", order.symbol)
        assertEquals(1, order.quantity)
    }

    @Test
    fun `신호가 없으면 사지 않는다`() {
        val broker = FakeBroker()
        scheduler(broker, EntrySignal.NO_TRADE, watched = listOf(SeedSymbol.KODEX_SEMICONDUCTOR)).checkEntries(krOpen)
        assertTrue(broker.orders.isEmpty())
    }

    @Test
    fun `저점 판단 진입은 보유 수량과 상관없이 신호가 뜨면 산다`() {
        val broker = FakeBroker()
        scheduler(
            broker,
            EntrySignal.BUY,
            watched = listOf(SeedSymbol.KODEX_SEMICONDUCTOR),
            portfolioService = portfolioServiceHolding("091160"),
        ).checkEntries(krOpen)
        assertEquals(1, broker.orders.size)
    }

    private fun buy(
        quantity: Int,
        price: String,
    ) = Trade(Market.KR, "005930", Side.BUY, quantity, BigDecimal(price), "0", "ok", initialFilledPrice = BigDecimal(price))

    private fun sell(
        quantity: Int,
        price: String,
    ) = Trade(Market.KR, "005930", Side.SELL, quantity, BigDecimal(price), "0", "ok", initialFilledPrice = BigDecimal(price))

    private fun tradeServiceWith(vararg trades: Trade): TradeService {
        val service = Mockito.mock(TradeService::class.java)
        Mockito.`when`(service.trades(Market.KR, "005930")).thenReturn(trades.toList())
        return service
    }

    private fun martingale(
        vararg trades: Trade,
        signal: EntrySignal = EntrySignal.NO_TRADE,
    ): FakeBroker {
        val broker = FakeBroker() // 현재가 10,000 / 호가 10원
        scheduler(broker, signal, portfolioService = portfolioServiceHeldBy(*trades), tradeService = tradeServiceWith(*trades))
            .checkEntries(krOpen)
        return broker
    }

    /** 잔고 테이블을 흉내낸다: 마지막 SELL 이후의 매수분이 그대로 잔고에 있다. */
    private fun portfolioServiceHeldBy(vararg trades: Trade): PortfolioService {
        val cycle = MartingaleCycle.from(trades.toList())
        val average = cycle.averagePrice ?: return noopPortfolioService()
        val zero = BigDecimal.ZERO
        val holding = HoldingView(Market.KR, "005930", cycle.quantity, average, average, zero, zero, zero)
        val summary =
            PortfolioSummary(
                "KRW", listOf(holding), zero, zero, zero, zero, zero, zero, zero, zero,
            )
        val service = Mockito.mock(PortfolioService::class.java)
        Mockito.`when`(service.snapshot()).thenReturn(PortfolioSnapshot(emptyList(), emptyMap(), mapOf("KRW" to summary)))
        return service
    }

    @Test
    fun `삼성전자 평단 대비 0,5퍼센트 하락하면 보유 수량만큼 더 산다`() {
        // 트리거 = 10,100 × 0.995 = 10,049.5 → 10,050 ≥ 현재가 10,000
        val order = martingale(buy(2, "10100")).orders.single()
        assertEquals(Side.BUY, order.side)
        assertEquals(2, order.quantity)
    }

    @Test
    fun `삼성전자 트리거 위면 신호가 있어도 추가 매수하지 않는다`() {
        // 트리거 = 10,000 × 0.995 = 9,950 < 현재가 10,000
        assertTrue(martingale(buy(2, "10000"), signal = EntrySignal.BUY).orders.isEmpty())
    }

    @Test
    fun `삼성전자 5단계 뒤엔 더 떨어져도 사지 않는다`() {
        val five = arrayOf(buy(1, "20000"), buy(2, "19000"), buy(4, "18000"), buy(8, "17000"), buy(16, "16000"))
        assertTrue(martingale(*five).orders.isEmpty())
    }

    @Test
    fun `삼성전자 4단계 다음 5단계 8주 주문이 리스크 가드를 통과해 나간다`() {
        // 금액 검증은 RiskGuardTest(27만원대 누적 843만원 < 1000만원). 여기선 스케줄러가 8주를 실제로 내는지만 본다.
        val order = martingale(buy(1, "10500"), buy(1, "10400"), buy(2, "10300"), buy(4, "10100")).orders.single()
        assertEquals(8, order.quantity)
    }

    @Test
    fun `마틴게일만 켜면 첫 진입은 하지 않는다 - 저점 판단 진입·5분 재매수는 별개 옵션`() {
        assertTrue(martingale(signal = EntrySignal.BUY).orders.isEmpty())
    }

    private fun rebuyScheduler(
        broker: FakeBroker,
        held: Boolean,
        bounce: Boolean = false,
        signal: EntrySignal = EntrySignal.NO_TRADE,
        buyQuantity: Int = 1,
    ) = scheduler(
        broker,
        signal,
        portfolioService = if (held) portfolioServiceHeldBy(buy(1, "10000")) else noopPortfolioService(),
        tradeService = tradeServiceWith(),
        configs = listOf(symbolStrategy(Market.KR, "005930", supportBounceEntry = bounce, periodicRebuy = true, buyQuantity = buyQuantity)),
    )

    @Test
    fun `5분 재매수 - 보유가 없으면 바로 1주 산다`() {
        val broker = FakeBroker()
        rebuyScheduler(broker, held = false).checkEntries(krOpen)
        assertEquals(1, broker.orders.single().quantity)
    }

    @Test
    fun `종목 설정의 매수 수량만큼 5분 재매수와 저점 판단 진입이 각각 산다`() {
        val broker = FakeBroker()
        rebuyScheduler(broker, held = false, bounce = true, signal = EntrySignal.BUY, buyQuantity = 3).checkEntries(krOpen)
        assertEquals(listOf(3, 3), broker.orders.map { it.quantity })
    }

    @Test
    fun `5분 재매수 - 주문 기록과 무관하게 5분마다 계속 돈다`() {
        val broker = FakeBroker()
        val s = rebuyScheduler(broker, held = false)
        s.checkEntries(krOpen)
        s.checkEntries(krOpen.plusMinutes(4))
        assertEquals(1, broker.orders.size)
        s.checkEntries(krOpen.plusMinutes(5))
        s.checkEntries(krOpen.plusMinutes(10))
        assertEquals(3, broker.orders.size)
    }

    @Test
    fun `5분 재매수 - 보유 중이면 사지 않는다`() {
        val broker = FakeBroker()
        rebuyScheduler(broker, held = true).checkEntries(krOpen)
        assertTrue(broker.orders.isEmpty())
    }

    @Test
    fun `5분 재매수와 저점 판단 진입은 서로 별개라 각자 주문한다`() {
        val broker = FakeBroker()
        rebuyScheduler(broker, held = false, bounce = true, signal = EntrySignal.BUY).checkEntries(krOpen)
        assertEquals(2, broker.orders.size)
    }

    @Test
    fun `매수 옵션이 모두 꺼져 있으면 신호가 있어도 사지 않는다`() {
        val broker = FakeBroker()
        scheduler(
            broker,
            EntrySignal.BUY,
            configs = listOf(symbolStrategy(Market.KR, "005930", supportBounceEntry = false, martingale = false)),
        )
            .checkEntries(krOpen)
        assertTrue(broker.orders.isEmpty())
    }

    @Test
    fun `DB 에 설정 행이 없는 종목은 사지 않는다`() {
        val broker = FakeBroker()
        scheduler(broker, EntrySignal.BUY, configs = emptyList()).checkEntries(krOpen)
        assertTrue(broker.orders.isEmpty())
    }

    @Test
    fun `DB 설정에서 마틴게일 값을 바꾸면 그대로 반영된다`() {
        // 하락 트리거 1% 로 설정: 직전 매수가 10,100 → 10,000(9,999 반올림) 에 닿아야 산다. 10,050 이면 안 산다.
        val onePercent =
            symbolStrategy(Market.KR, "005930", martingale = true).let {
                SymbolStrategy(
                    it.market, it.symbol, it.displayName, it.takeProfitPercent, null, true, java.math.BigDecimal("1"), 3, 5,
                    java.math.BigDecimal("3"),
                )
            }
        val broker = FakeBroker()
        scheduler(
            broker,
            EntrySignal.NO_TRADE,
            portfolioService = portfolioServiceHeldBy(buy(2, "10100")),
            tradeService = tradeServiceWith(buy(2, "10100")),
            configs = listOf(onePercent),
        )
            .checkEntries(krOpen)
        assertEquals(4, broker.orders.single().quantity) // 보유 2주 × (배수 3 − 1)

        val broker2 = FakeBroker()
        scheduler(
            broker2,
            EntrySignal.NO_TRADE,
            portfolioService = portfolioServiceHeldBy(buy(2, "10050")),
            tradeService = tradeServiceWith(buy(2, "10050")),
            configs = listOf(onePercent),
        )
            .checkEntries(krOpen)
        assertTrue(broker2.orders.isEmpty())
    }

    @Test
    fun `자동매매 대상이 아니면 분봉은 모으되 사지는 않는다`() {
        val broker = FakeBroker()
        val marketDataService = marketDataServiceStub()
        scheduler(broker, EntrySignal.BUY, watched = listOf(SeedSymbol.SK_HYNIX), marketDataService = marketDataService)
            .checkEntries(krOpen)

        assertTrue(broker.orders.isEmpty())
        Mockito.verify(marketDataService).fetchAndStoreRecentMinutes(eqNonNull(Market.KR), eqNonNull("000660"), anyNonNull())
    }

    @Test
    fun `한 번 신호가 뜨면 쿨다운 동안 다시 사지 않는다`() {
        val broker = FakeBroker()
        val s = scheduler(broker, EntrySignal.BUY, watched = listOf(SeedSymbol.KODEX_SEMICONDUCTOR))
        s.checkEntries(krOpen)
        s.checkEntries(krOpen.plusMinutes(1))
        assertEquals(1, broker.orders.size)

        s.checkEntries(krOpen.plusMinutes(11))
        assertEquals(2, broker.orders.size)
    }

    @Test
    fun `장 시간이 아니면 아무것도 안 한다`() {
        val broker = FakeBroker()
        scheduler(broker, EntrySignal.BUY).checkEntries(krOpen.withHour(16))
        assertTrue(broker.orders.isEmpty())
    }
}

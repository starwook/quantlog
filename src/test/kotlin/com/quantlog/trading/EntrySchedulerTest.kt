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
import com.quantlog.position.HoldingView
import com.quantlog.position.PortfolioService
import com.quantlog.position.PortfolioSnapshot
import com.quantlog.position.PortfolioSummary
import com.quantlog.position.Trade
import com.quantlog.position.TradeService
import com.quantlog.strategy.EntrySignal
import com.quantlog.strategy.MartingaleProperties
import com.quantlog.strategy.MartingaleRule
import com.quantlog.strategy.SupportBounceEntryRule
import com.quantlog.watchlist.WatchedSymbol
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
        val holding = HoldingView(Market.KR, symbol, 1, BigDecimal("9000"), BigDecimal("10000"), false, zero, zero, zero)
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
        signal: EntrySignal,
        watched: List<WatchedSymbol> = listOf(WatchedSymbol.SAMSUNG),
        portfolioService: PortfolioService = noopPortfolioService(),
        marketDataService: MarketDataService = marketDataServiceStub(),
        tradeService: TradeService = Mockito.mock(TradeService::class.java),
    ) = EntryScheduler(
        broker,
        RiskGuard(RiskProperties(), noopPortfolioService()),
        entryRuleReturning(signal),
        MartingaleRule(MartingaleProperties()),
        marketDataService,
        tradeService,
        portfolioService,
        watched,
        EntryProperties(enabled = true),
    )

    @Test
    fun `매수 신호가 뜨면 소량 매수한다`() {
        val broker = FakeBroker()
        scheduler(broker, EntrySignal.BUY).checkEntries(krOpen)

        val order = broker.orders.single()
        assertEquals(Side.BUY, order.side)
        assertEquals("005930", order.symbol)
        assertEquals(1, order.quantity)
    }

    @Test
    fun `신호가 없으면 사지 않는다`() {
        val broker = FakeBroker()
        scheduler(broker, EntrySignal.NO_TRADE).checkEntries(krOpen)
        assertTrue(broker.orders.isEmpty())
    }

    @Test
    fun `martingale 아닌 종목은 이미 보유 중이면 신호가 있어도 사지 않는다`() {
        val broker = FakeBroker()
        scheduler(
            broker,
            EntrySignal.BUY,
            watched = listOf(WatchedSymbol.KODEX_SEMICONDUCTOR),
            portfolioService = portfolioServiceHolding("091160"),
        ).checkEntries(krOpen)
        assertTrue(broker.orders.isEmpty())
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
        scheduler(broker, signal, tradeService = tradeServiceWith(*trades)).checkEntries(krOpen)
        return broker
    }

    @Test
    fun `삼성전자 직전 매수가 대비 0,5퍼센트 하락하면 직전 수량의 2배를 산다`() {
        // 트리거 = 10,100 × 0.995 = 10,049.5 → 10,050 ≥ 현재가 10,000
        val order = martingale(buy(2, "10100")).orders.single()
        assertEquals(Side.BUY, order.side)
        assertEquals(4, order.quantity)
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
    fun `삼성전자 4단계 다음 5단계 16주 주문이 리스크 가드를 통과해 나간다`() {
        // 금액 검증은 RiskGuardTest(27만원대 누적 843만원 < 1000만원). 여기선 스케줄러가 16주를 실제로 내는지만 본다.
        val order = martingale(buy(1, "10500"), buy(2, "10400"), buy(4, "10300"), buy(8, "10100")).orders.single()
        assertEquals(16, order.quantity)
    }

    @Test
    fun `익절 뒤 매도가 -0,5퍼센트에 닿으면 첫 1주부터 재진입 - 신호 불필요`() {
        // 평단 9,900 → 10,000 에 익절. 트리거 = 10,000 × 0.995 = 9,950 → 현재가 10,000 은 위라 대기
        assertTrue(martingale(buy(1, "9900"), sell(1, "10000"), signal = EntrySignal.BUY).orders.isEmpty())
        // 10,050 에 익절 → 트리거 10,000(9,999.75 반올림) ≥ 현재가 → 재진입
        val order = martingale(buy(1, "9900"), sell(1, "10050")).orders.single()
        assertEquals(1, order.quantity)
    }

    @Test
    fun `손절 뒤엔 매도가 -1퍼센트에 닿으면 재진입한다`() {
        // 평단 10,500 → 10,100 손절. 트리거 = 10,100 × 0.99 = 9,999 → 10,000 ≥ 현재가
        assertEquals(1, martingale(buy(1, "10500"), sell(1, "10100")).orders.single().quantity)
        // 10,000 손절 → 트리거 9,900 < 현재가 → 대기 (SupportBounce 신호도 안 봄)
        assertTrue(martingale(buy(1, "10500"), sell(1, "10000"), signal = EntrySignal.BUY).orders.isEmpty())
    }

    @Test
    fun `삼성전자 첫 사이클은 매도 기록이 없으면 SupportBounce 신호로 시작한다`() {
        assertEquals(1, martingale(signal = EntrySignal.BUY).orders.single().quantity)
        assertTrue(martingale(signal = EntrySignal.NO_TRADE).orders.isEmpty())
    }

    @Test
    fun `자동매매 대상이 아니면 분봉은 모으되 사지는 않는다`() {
        val broker = FakeBroker()
        val marketDataService = marketDataServiceStub()
        scheduler(broker, EntrySignal.BUY, watched = listOf(WatchedSymbol.SK_HYNIX), marketDataService = marketDataService)
            .checkEntries(krOpen)

        assertTrue(broker.orders.isEmpty())
        Mockito.verify(marketDataService).fetchAndStoreRecentMinutes(eqNonNull(Market.KR), eqNonNull("000660"), anyNonNull())
    }

    @Test
    fun `한 번 신호가 뜨면 쿨다운 동안 다시 사지 않는다`() {
        val broker = FakeBroker()
        val s = scheduler(broker, EntrySignal.BUY)
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

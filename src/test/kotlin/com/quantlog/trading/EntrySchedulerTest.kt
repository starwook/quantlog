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
import com.quantlog.position.TradeService
import com.quantlog.strategy.EntrySignal
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
    ) = EntryScheduler(
        broker,
        RiskGuard(RiskProperties(), noopPortfolioService()),
        entryRuleReturning(signal),
        marketDataService,
        Mockito.mock(TradeService::class.java),
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
    fun `이미 보유 중이면 신호가 있어도 사지 않는다`() {
        val broker = FakeBroker()
        scheduler(broker, EntrySignal.BUY, portfolioService = portfolioServiceHolding("005930")).checkEntries(krOpen)
        assertTrue(broker.orders.isEmpty())
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

package com.quantlog.trading

import com.quantlog.broker.BrokerClient
import com.quantlog.broker.BuyingPower
import com.quantlog.broker.CancelRequest
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
import com.quantlog.position.PortfolioSnapshot
import com.quantlog.position.Trade
import com.quantlog.position.TradeService
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

class MartingaleServiceTest {
    private class FakeBroker : BrokerClient {
        val orders = mutableListOf<OrderRequest>()
        val cancels = mutableListOf<CancelRequest>()
        var quoteCalls = 0
        var cancelAttempts = 0
        var cancelFails = false

        override fun quote(
            market: Market,
            symbol: String,
        ): Quote {
            quoteCalls++
            return Quote(BigDecimal("10000"), BigDecimal("10"))
        }

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
            return OrderReceipt("A${orders.size}", "ok", "00950")
        }

        override fun cancelOrder(request: CancelRequest) {
            cancelAttempts++
            if (cancelFails) throw IllegalStateException("이미 체결됨")
            cancels += request
        }
    }

    /** Mockito.any() 는 코틀린 non-null 타입 파라미터에 null 을 넘겨 NPE 를 낸다. */
    private fun <T> anyNonNull(): T = Mockito.any<T>()

    private val symbol = "005930"
    private val broker = FakeBroker()
    private val tradeService = Mockito.mock(TradeService::class.java)
    private val holdingSync = Mockito.mock(HoldingSyncService::class.java)
    private val repository = Mockito.mock(AccountHoldingRepository::class.java)
    private val riskGuard = Mockito.mock(RiskGuard::class.java)

    /** 2026-09-29(화) 10:00 KST: 국내 정규장. */
    private val krOpen = ZonedDateTime.of(2026, 9, 29, 10, 0, 0, 0, ZoneId.of("Asia/Seoul"))

    private fun service(martingale: Boolean = true) =
        MartingaleService(
            broker,
            riskGuard,
            symbolStrategyServiceOf(symbolStrategy(Market.KR, symbol, martingale = martingale)),
            tradeService,
            repository,
            holdingSync,
            EntryProperties(enabled = true),
        )

    /** DB 잔고 사본: [quantity]주, 평단 [avg]. 현재가는 10,000(FakeBroker) → 평단 10,100 이면 트리거(10,050) 이하다. */
    private fun holding(
        quantity: Int,
        avg: String = "10100",
    ) {
        val row = AccountHolding(Market.KR, symbol, quantity, BigDecimal(avg), BigDecimal("10000"))
        Mockito.`when`(repository.findByMarketAndSymbol(Market.KR, symbol)).thenReturn(row)
        Mockito.`when`(tradeService.trades(Market.KR, symbol)).thenReturn(emptyList())
    }

    private fun tick(
        service: MartingaleService,
        price: String = "10000",
        at: ZonedDateTime = krOpen,
    ) = service.checkSymbol(Market.KR, symbol, BigDecimal(price), at)

    @Test
    fun `평단 트리거 이하 틱이 오면 보유 수량만큼 지정가 매수한다`() {
        holding(2)

        tick(service())

        val order = broker.orders.single()
        assertEquals(Side.BUY, order.side)
        assertEquals(2, order.quantity)
        // 현재가 10,000 + 한 호가(10) = 10,010
        assertEquals(0, BigDecimal("10010").compareTo(order.limitPrice))
    }

    @Test
    fun `틱이 트리거보다 한참 위면 시세 조회도 하지 않고 지나간다`() {
        holding(2)

        tick(service(), price = "11000")

        assertTrue(broker.orders.isEmpty())
        assertEquals(0, broker.quoteCalls)
    }

    @Test
    fun `사전 비교는 통과해도 규칙 트리거 위면 사지 않는다`() {
        holding(2, avg = "10040") // 트리거 9,990 < 현재가 10,000 (사전 비교 한계 10,019 이하라 시세까지 본다)

        tick(service(), price = "10000")

        assertTrue(broker.orders.isEmpty())
        assertEquals(1, broker.quoteCalls)
    }

    @Test
    fun `마틴게일이 꺼져 있거나 보유가 없거나 장 시간이 아니면 사지 않는다`() {
        holding(2)
        tick(service(martingale = false))

        Mockito.`when`(repository.findByMarketAndSymbol(Market.KR, symbol)).thenReturn(null)
        tick(service())

        holding(2)
        tick(service(), at = ZonedDateTime.of(2026, 9, 29, 15, 25, 0, 0, ZoneId.of("Asia/Seoul")))

        assertTrue(broker.orders.isEmpty())
    }

    @Test
    fun `주문을 낸 종목은 체결이 반영될 때까지 단계 진행 중이라 틱이 계속 와도 다시 사지 않는다`() {
        holding(2)
        val service = service()

        tick(service)
        tick(service)
        tick(service)

        assertEquals(1, broker.orders.size)
    }

    @Test
    fun `체결이 반영되면 단계 진행 중이 풀려 다음 틱부터 다시 판정한다`() {
        holding(2)
        val service = service()
        tick(service)

        Mockito.`when`(holdingSync.isOrderFilled("A1")).thenReturn(true)
        holding(4, avg = "10050") // 체결통보가 DB 를 4주·평단 10,050 으로 고쳤다 → 트리거 10,000 이하라 다음 단계

        tick(service)

        assertEquals(2, broker.orders.size)
        assertEquals(4, broker.orders.last().quantity)
    }

    @Test
    fun `10초가 지나도 미체결이면 주문을 취소하고 쿨다운 없이 다음 틱에 다시 판정한다`() {
        holding(2)
        val trade = Trade(Market.KR, symbol, Side.BUY, 2, BigDecimal("10050"), "A1", "ok")
        Mockito.`when`(tradeService.findByOrderNo(Market.KR, "A1")).thenReturn(trade)
        val service = service()
        tick(service)

        service.expirePending(krOpen.plusSeconds(5))
        assertTrue(broker.cancels.isEmpty())

        service.expirePending(krOpen.plusSeconds(11))
        val cancel = broker.cancels.single()
        assertEquals("A1", cancel.orderNo)
        assertEquals("00950", cancel.branchNo)
        Mockito.verify(tradeService).markCanceled(trade)

        tick(service, at = krOpen.plusSeconds(11))
        assertEquals(2, broker.orders.size)
    }

    @Test
    fun `취소가 거부되면 이미 체결됐을 수 있어 단계 진행 중을 유지한다`() {
        holding(2)
        val service = service()
        tick(service)
        broker.cancelFails = true

        service.expirePending(krOpen.plusSeconds(11))
        tick(service, at = krOpen.plusSeconds(12))

        assertEquals(1, broker.orders.size)
    }

    @Test
    fun `취소가 계속 거부돼 첫 시도로부터 2분이 지나면 포기하고 다시 판정한다`() {
        holding(2)
        val service = service()
        tick(service)
        broker.cancelFails = true

        service.expirePending(krOpen.plusSeconds(11))
        service.expirePending(krOpen.plusSeconds(11 + 121))
        tick(service, at = krOpen.plusSeconds(11 + 122))

        assertEquals(2, broker.orders.size)
    }

    @Test
    fun `취소가 계속 거부돼도 5초 간격으로만 다시 시도한다`() {
        holding(2)
        val service = service()
        tick(service)
        broker.cancelFails = true

        service.expirePending(krOpen.plusSeconds(11))
        service.expirePending(krOpen.plusSeconds(12))
        service.expirePending(krOpen.plusSeconds(14))
        assertEquals(1, broker.cancelAttempts)

        service.expirePending(krOpen.plusSeconds(16))
        assertEquals(2, broker.cancelAttempts)
    }

    @Test
    fun `이미 체결 반영된 주문은 만료 점검에서 취소하지 않는다`() {
        holding(2)
        val service = service()
        tick(service)
        Mockito.`when`(holdingSync.isOrderFilled("A1")).thenReturn(true)

        service.expirePending(krOpen.plusSeconds(30))

        assertTrue(broker.cancels.isEmpty())
    }

    @Test
    fun `리스크 가드가 거부하면 쿨다운 동안 다시 시도하지 않는다`() {
        holding(2)
        Mockito.doThrow(RiskViolationException("자본 배분 초과")).`when`(riskGuard).checkBuy(anyNonNull())
        val service = service()

        runCatching { tick(service) }
        tick(service, at = krOpen.plusSeconds(1))
        tick(service, at = krOpen.plusSeconds(2))

        assertTrue(broker.orders.isEmpty())
        // 첫 시도만 시세를 봤다(쿨다운 안에서는 시세 조회 전에 돌아간다)
        assertEquals(1, broker.quoteCalls)
    }

    @Test
    fun `리스크 가드는 실제 객체로도 통과한다`() {
        holding(2)
        val portfolio = Mockito.mock(PortfolioService::class.java)
        Mockito.`when`(portfolio.snapshot()).thenReturn(PortfolioSnapshot(emptyList(), emptyMap(), emptyMap()))
        val service =
            MartingaleService(
                broker,
                RiskGuard(RiskProperties(), portfolio),
                symbolStrategyServiceOf(symbolStrategy(Market.KR, symbol, martingale = true)),
                tradeService,
                repository,
                holdingSync,
                EntryProperties(enabled = true),
            )

        tick(service)

        assertEquals(1, broker.orders.size)
    }
}

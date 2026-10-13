package com.quantlog.position

import com.quantlog.broker.Holding
import com.quantlog.broker.Market
import com.quantlog.broker.Side
import com.quantlog.sync.MismatchLog
import com.quantlog.watchlist.symbolStrategyServiceOf
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 체결통보가 한투 체결보다 늦게 와도(2026-10-12 실측 최대 18.8초) 잔고 동기화가 보유를 틀리게 만들지 않는지.
 * 잔고는 당일 누적 매수·매도 수량이 앱이 반영한 원장과 같을 때만 비교·덮어쓴다.
 */
class HoldingBalanceSyncTimingTest {
    private val symbol = "226490"
    private val repository = Mockito.mock(AccountHoldingRepository::class.java)
    private val placed = mutableListOf<Trade>()

    /** 앱이 낸 주문 기록을 [placed] 로 돌려주는 가짜 저장소. */
    private val tradeRepository =
        Mockito.mock(TradeRepository::class.java) { call ->
            if (call.method.name == "findAllByMarketAndSymbolAndExecutedAtAfter") placed.toList() else Mockito.RETURNS_DEFAULTS.answer(call)
        }
    private val ledger = InMemoryBrokerFills()
    private val events = mutableListOf<Any>()
    private val service = HoldingSyncFixture(repository, tradeRepository, ledger, symbolStrategyServiceOf(), recordingPublisher(events))
    private val at = Instant.parse("2026-10-12T05:44:00Z")

    private fun row(
        quantity: Int,
        avg: String,
    ) = AccountHolding(Market.KR, symbol, quantity, BigDecimal(avg), BigDecimal("66000")).also {
        Mockito.`when`(repository.findAll()).thenReturn(listOf(it))
        Mockito.`when`(repository.findByMarketAndSymbol(Market.KR, symbol)).thenReturn(it)
    }

    private fun kis(
        quantity: Int,
        avg: String,
        bought: Int?,
        sold: Int?,
    ) = Holding(
        Market.KR,
        symbol,
        "KODEX 코스피",
        BigDecimal(quantity),
        BigDecimal(avg),
        BigDecimal("65900"),
        bought?.let(::BigDecimal),
        sold?.let(::BigDecimal),
    )

    private fun fill(
        side: Side,
        quantity: Int,
        price: String,
        orderNo: String,
        receivedAt: Instant,
    ) = service.applyRecorded(ledger, fillNotice(side, quantity, price, orderNo, symbol), receivedAt)

    @Test
    fun `잔고가 체결통보보다 먼저 와도 같은 체결을 두 번 반영하지 않고 매도 직전 평단이 맞다`() {
        val holding = row(10, "66065")
        fill(Side.BUY, 10, "66065", "0000037320", at.minusSeconds(300)) // 이미 반영된 첫 매수
        holding.update(10, BigDecimal("66065"), BigDecimal("66000"))
        orderPlaced("0000037728", Side.BUY, 10, at.plusSeconds(20))
        orderPlaced("0000037742", Side.SELL, 10, at.plusSeconds(24))

        MismatchLog().use { captured ->
            // 한투는 14:44:20~26 에 추가 매수 10주 + 매도 10주를 끝냈다. 이 잔고(14:44:22 조회)는 그 뒤 값이지만 체결통보는 아직 안 왔다.
            val changes = service.sync(listOf(kis(10, "65980", bought = 20, sold = 10)), at.plusSeconds(22))
            assertTrue(changes.isEmpty())
            assertEquals(10, holding.quantity)
            assertEquals(0, BigDecimal("66065").compareTo(holding.avgCost))

            // 통보가 늦게 도착한다(매수 18.8초, 매도 15.1초 지연)
            fill(Side.BUY, 10, "65895", "0000037728", at.plusSeconds(38))
            fill(Side.SELL, 10, "65880", "0000037742", at.plusSeconds(41))

            val sell = events.filterIsInstance<FillAppliedEvent>().last { it.side == Side.SELL }
            assertEquals(0, BigDecimal("65980").compareTo(sell.avgCostBefore), "매도 직전 평단은 ${sell.avgCostBefore}")

            // 이제 같은 시점(누적 20/10)이라 비교가 되고, 보유와 평단이 증권사와 같아 보고할 것이 없다
            assertTrue(service.sync(listOf(kis(10, "65980", bought = 20, sold = 10)), at.plusSeconds(48)).isEmpty())
            assertTrue(captured.messages.isEmpty(), captured.messages.toString())
        }
        assertEquals(10, holding.quantity)
        assertEquals(0, BigDecimal("65980").compareTo(holding.avgCost))
    }

    @Test
    fun `기다릴 주문이 없는데 당일 누적이 다르면 통보를 놓친 것이라 바로 증권사 값으로 고친다`() {
        val holding = row(10, "66065")
        fill(Side.BUY, 10, "66065", "0000037320", at.minusSeconds(300))
        holding.update(10, BigDecimal("66065"), BigDecimal("66000"))

        MismatchLog().use { captured ->
            assertEquals(1, service.sync(listOf(kis(15, "66000", bought = 25, sold = 0)), at).size)

            assertEquals(15, holding.quantity)
            assertTrue("DB=10주" in captured.messages.single() && "증권사=15주" in captured.messages.single(), captured.messages.toString())
        }
    }

    @Test
    fun `이미 체결이 다 확인된 주문은 기다리는 주문이 아니다`() {
        val holding = row(10, "66065")
        fill(Side.BUY, 10, "66065", "0000037320", at.minusSeconds(300))
        holding.update(10, BigDecimal("66065"), BigDecimal("66000"))
        orderPlaced("0000037320", Side.BUY, 10, at.minusSeconds(300))

        assertEquals(1, service.sync(listOf(kis(15, "66000", bought = 25, sold = 0)), at).size)
    }

    @Test
    fun `취소된 주문도 기다리는 주문이 아니다`() {
        val holding = row(10, "66065")
        orderPlaced("0000037500", Side.BUY, 5, at.minusSeconds(20)).cancel()

        assertEquals(1, service.sync(listOf(kis(15, "66000", bought = 5, sold = 0)), at).size)
        assertEquals(15, holding.quantity)
    }

    @Test
    fun `주문의 체결통보를 기다리는 동안은 수량·평단을 건드리지 않고 통보가 오면 그 뒤에 비교한다`() {
        val holding = row(10, "66065")
        orderPlaced("0000037500", Side.BUY, 5, at.minusSeconds(20))

        // 잔고는 이미 체결된 15주인데 통보가 아직 없다
        assertTrue(service.sync(listOf(kis(15, "66043.333333", bought = 5, sold = 0)), at).isEmpty())
        assertEquals(10, holding.quantity)

        fill(Side.BUY, 5, "66000", "0000037500", at.plusSeconds(15))
        assertTrue(service.sync(listOf(kis(15, "66043.333333", bought = 5, sold = 0)), at.plusSeconds(20)).isEmpty())
        assertEquals(15, holding.quantity)
    }

    @Test
    fun `잔고에 당일 누적이 없어도 기다릴 주문이 없으면 바로 고치고 있으면 기다린다`() {
        val holding = row(10, "66065")
        orderPlaced("0000037500", Side.BUY, 2, at.minusSeconds(5))

        assertTrue(service.sync(listOf(kis(12, "66000", bought = null, sold = null)), at).isEmpty())
        assertEquals(10, holding.quantity)

        placed.clear()
        assertEquals(1, service.sync(listOf(kis(12, "66000", bought = null, sold = null)), at.plusSeconds(1)).size)
        assertEquals(12, holding.quantity)
    }

    @Test
    fun `증권사 잔고에서 사라진 종목은 매도 주문을 기다리는 동안만 지우지 않는다`() {
        val holding = row(10, "66065")
        orderPlaced("0000037742", Side.SELL, 10, at.minusSeconds(3))

        assertTrue(service.sync(emptyList(), at).isEmpty())
        Mockito.verify(repository, Mockito.never()).delete(holding)

        // 통보가 와서 보유가 0 이 되면 잔고와 같아진다
        fill(Side.SELL, 10, "65880", "0000037742", at.plusSeconds(15))
        Mockito.verify(repository).delete(holding)
    }

    @Test
    fun `3분보다 오래된 주문은 기다리는 주문으로 보지 않는다`() {
        row(10, "66065")

        service.sync(listOf(kis(12, "66000", bought = 5, sold = 0)), at)

        Mockito.verify(tradeRepository).findAllByMarketAndSymbolAndExecutedAtAfter(Market.KR, symbol, at.minusSeconds(180))
    }

    /** 앱이 낸 주문 기록을 만들어 [tradeRepository] 가 돌려주게 한다. */
    private fun orderPlaced(
        orderNo: String,
        side: Side,
        quantity: Int,
        placedAt: Instant,
    ): Trade {
        val trade = Trade(Market.KR, symbol, side, quantity, BigDecimal("66000"), orderNo, "ok", executedAt = placedAt)
        placed += trade
        return trade
    }
}

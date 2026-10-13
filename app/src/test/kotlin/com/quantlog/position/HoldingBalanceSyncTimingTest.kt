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
    private val tradeRepository = Mockito.mock(TradeRepository::class.java)
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
    fun `당일 누적이 원장과 다른 어긋남은 60초 동안 기다리고 그래도 이어지면 증권사 값으로 고친다`() {
        val holding = row(10, "66065")
        fill(Side.BUY, 10, "66065", "0000037320", at.minusSeconds(300))
        holding.update(10, BigDecimal("66065"), BigDecimal("66000"))
        val missed = kis(15, "66000", bought = 25, sold = 0) // 통보를 놓친 매수 15주가 있는 잔고

        MismatchLog().use { captured ->
            assertTrue(service.sync(listOf(missed), at).isEmpty())
            assertTrue(service.sync(listOf(missed), at.plusSeconds(59)).isEmpty())
            assertEquals(10, holding.quantity)
            assertTrue(captured.messages.isEmpty())

            assertEquals(1, service.sync(listOf(missed), at.plusSeconds(60)).size)
            assertEquals(15, holding.quantity)
            assertTrue("DB=10주" in captured.messages.single() && "증권사=15주" in captured.messages.single(), captured.messages.toString())
        }
    }

    @Test
    fun `기다리는 동안 이 종목 체결이 반영되면 기다리는 시간을 처음부터 다시 센다`() {
        val holding = row(10, "66065")
        fill(Side.BUY, 10, "66065", "0000037320", at.minusSeconds(300))
        holding.update(10, BigDecimal("66065"), BigDecimal("66000"))

        service.sync(listOf(kis(15, "66000", bought = 30, sold = 0)), at)
        fill(Side.BUY, 5, "66000", "0000037500", at.plusSeconds(30)) // 통보가 계속 들어오는 중

        assertTrue(service.sync(listOf(kis(15, "66000", bought = 30, sold = 0)), at.plusSeconds(61)).isEmpty())
        assertEquals(15, holding.quantity)
        assertTrue(holding.avgCost > BigDecimal("66040"), "평단은 체결로 계산한 값 그대로여야 한다: ${holding.avgCost}")
    }

    @Test
    fun `잔고에 당일 누적이 없으면 같은 시점인지 알 수 없어 기다린다`() {
        val holding = row(10, "66065")

        assertTrue(service.sync(listOf(kis(12, "66000", bought = null, sold = null)), at).isEmpty())
        assertEquals(10, holding.quantity)
        assertEquals(1, service.sync(listOf(kis(12, "66000", bought = null, sold = null)), at.plusSeconds(60)).size)
        assertEquals(12, holding.quantity)
    }

    @Test
    fun `증권사 잔고에서 사라진 종목도 통보를 기다린 뒤에만 지운다`() {
        val holding = row(10, "66065")

        assertTrue(service.sync(emptyList(), at).isEmpty())
        Mockito.verify(repository, Mockito.never()).delete(holding)

        // 그 사이 매도 통보가 와서 보유가 0 이 되면 잔고와 같아져 기다림이 끝난다
        fill(Side.SELL, 10, "65880", "0000037742", at.plusSeconds(15))
        Mockito.verify(repository).delete(holding)
        assertTrue(service.sync(emptyList(), at.plusSeconds(70)).isEmpty())
    }
}

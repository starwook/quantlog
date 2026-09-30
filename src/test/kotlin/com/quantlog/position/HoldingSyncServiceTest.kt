package com.quantlog.position

import com.quantlog.broker.Holding
import com.quantlog.broker.Market
import com.quantlog.broker.Side
import com.quantlog.watchlist.symbolStrategyServiceOf
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HoldingSyncServiceTest {
    private val symbol = "005930"
    private val repository = Mockito.mock(AccountHoldingRepository::class.java)
    private val tradeRepository = Mockito.mock(TradeRepository::class.java)
    private val service = HoldingSyncService(repository, tradeRepository, symbolStrategyServiceOf())
    private val now = Instant.parse("2026-09-30T05:00:00Z")

    private fun kis(
        quantity: Int,
        average: String,
        sym: String = symbol,
    ) = Holding(Market.KR, sym, "KODEX", BigDecimal(quantity), BigDecimal(average), BigDecimal("9000"))

    private fun row(
        quantity: Int,
        avg: String,
        sym: String = symbol,
    ) = AccountHolding(Market.KR, sym, quantity, BigDecimal(avg), BigDecimal("9000"))

    private fun trade(at: Instant) = Trade(Market.KR, symbol, Side.BUY, 1, BigDecimal("9000"), "1", "ok", executedAt = at)

    @Test
    fun `KIS 잔고에만 있는 종목은 새로 저장한다`() {
        Mockito.`when`(repository.findAll()).thenReturn(emptyList())

        val changes = service.sync(listOf(kis(5, "8900")), now)

        val saved = org.mockito.ArgumentCaptor.forClass(AccountHolding::class.java)
        verify(repository).save(saved.capture() ?: row(0, "0"))
        assertEquals(5, saved.value.quantity)
        assertEquals(0, BigDecimal("8900").compareTo(saved.value.avgCost))
        assertEquals(1, changes.size)
    }

    @Test
    fun `이미 있는 종목은 수량·평단·현재가를 KIS 값으로 덮어쓴다`() {
        val existing = row(2, "9000")
        Mockito.`when`(repository.findAll()).thenReturn(listOf(existing))

        val changes = service.sync(listOf(kis(5, "8900")), now)

        assertEquals(5, existing.quantity)
        assertEquals(0, BigDecimal("8900").compareTo(existing.avgCost))
        assertEquals(1, changes.size)
        verify(repository, never()).save(any(AccountHolding::class.java))
    }

    @Test
    fun `KIS 잔고에서 사라진 종목은 삭제한다`() {
        val existing = row(2, "9000")
        Mockito.`when`(repository.findAll()).thenReturn(listOf(existing))

        val changes = service.sync(emptyList(), now)

        verify(repository).delete(existing)
        assertEquals(1, changes.size)
    }

    @Test
    fun `수량이 같으면 바뀐 내용 없음`() {
        Mockito.`when`(repository.findAll()).thenReturn(listOf(row(5, "8900")))

        assertTrue(service.sync(listOf(kis(5, "8900")), now).isEmpty())
    }

    @Test
    fun `첫 동기화 전에는 모든 종목이 미반영 상태다`() {
        assertTrue(service.hasUnsyncedTrade(Market.KR, symbol))
    }

    @Test
    fun `동기화 뒤에 낸 주문만 미반영으로 본다`() {
        Mockito.`when`(repository.findAll()).thenReturn(emptyList())
        service.sync(emptyList(), now)

        Mockito.`when`(tradeRepository.findFirstByMarketAndSymbolOrderByExecutedAtDesc(Market.KR, symbol))
            .thenReturn(trade(now.minusSeconds(5)))
        assertFalse(service.hasUnsyncedTrade(Market.KR, symbol))

        Mockito.`when`(tradeRepository.findFirstByMarketAndSymbolOrderByExecutedAtDesc(Market.KR, symbol))
            .thenReturn(trade(now.plusSeconds(1)))
        assertTrue(service.hasUnsyncedTrade(Market.KR, symbol))
    }
}

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
    private val ledger = InMemoryBrokerFills()
    private val service =
        HoldingSyncFixture(repository, tradeRepository, ledger, symbolStrategyServiceOf(), recordingPublisher())
    private val now = Instant.parse("2026-09-30T05:00:00Z")

    private fun kis(
        quantity: Int,
        average: String,
        sym: String = symbol,
    ) = Holding(Market.KR, sym, "KODEX", BigDecimal(quantity), BigDecimal(average), BigDecimal("9000"), BigDecimal.ZERO, BigDecimal.ZERO)

    private fun row(
        quantity: Int,
        avg: String,
        sym: String = symbol,
    ) = AccountHolding(Market.KR, sym, quantity, BigDecimal(avg), BigDecimal("9000"))

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
        assertTrue(service.hasUnfinishedOrder(Market.KR, symbol))
    }

    @Test
    fun `끝났다고 확인된 주문만 더 기다리지 않는다`() {
        Mockito.`when`(repository.findAll()).thenReturn(emptyList())
        service.sync(emptyList(), Instant.now())
        val placed = Trade(Market.KR, symbol, Side.BUY, 1, BigDecimal("9000"), "E1", "ok", executedAt = Instant.now().minusSeconds(5))
        Mockito.`when`(tradeRepository.findFirstByMarketAndSymbolOrderByExecutedAtDesc(Market.KR, symbol)).thenReturn(placed)
        Mockito.`when`(tradeRepository.findAllByMarketAndOrderNo(Market.KR, "E1")).thenReturn(listOf(placed))
        // 잔고 동기화보다 먼저 낸 주문이어도, 끝났다고 확인되기 전에는 기다린다(2026-11-04: 동기화 시각 비교를 뺐다).
        assertTrue(service.hasUnfinishedOrder(Market.KR, symbol))

        service.fillApplier.applyInquiry(
            placed,
            com.quantlog.broker.OrderFillTotal("E1", 1, BigDecimal("9000"), BigDecimal("9000"), 0),
            Instant.now(),
        )
        assertFalse(service.hasUnfinishedOrder(Market.KR, symbol))
    }

    @Test
    fun `동기화가 지나가도 방금 낸 주문의 체결통보가 안 왔으면 미반영으로 본다`() {
        Mockito.`when`(repository.findAll()).thenReturn(emptyList())
        service.sync(emptyList(), now)
        val recent = Trade(Market.KR, symbol, Side.BUY, 1, BigDecimal("9000"), "1", "ok", executedAt = Instant.now().minusSeconds(30))
        Mockito.`when`(tradeRepository.findFirstByMarketAndSymbolOrderByExecutedAtDesc(Market.KR, symbol)).thenReturn(recent)
        assertTrue(service.hasUnfinishedOrder(Market.KR, symbol))

        // 취소만으로는 끝난 게 아니다 — 취소 전 일부 체결의 통보가 빠졌을 수 있어 체결조회로 확인될 때까지 기다린다(2026-10-14).
        recent.cancel()
        assertTrue(service.hasUnfinishedOrder(Market.KR, symbol))
    }

    @Test
    fun `시간이 지나도 끝났다고 확인되지 않은 오늘 주문은 계속 기다린다`() {
        Mockito.`when`(repository.findAll()).thenReturn(emptyList())
        service.sync(emptyList(), Instant.now())
        // 4분 전에 낸 매수. 체결통보가 빠져 끝남 확인이 없다 — 예전엔 3분이 지나면 풀어 낡은 보유로 다음 주문이 나갈 수 있었다(2026-11-04 폐기).
        val old = Trade(Market.KR, symbol, Side.BUY, 1, BigDecimal("9000"), "O1", "ok", executedAt = Instant.now().minusSeconds(240))
        Mockito.`when`(tradeRepository.findFirstByMarketAndSymbolOrderByExecutedAtDesc(Market.KR, symbol)).thenReturn(old)
        Mockito.`when`(tradeRepository.findAllByMarketAndOrderNo(Market.KR, "O1")).thenReturn(listOf(old))

        assertTrue(service.hasUnfinishedOrder(Market.KR, symbol))
    }

    @Test
    fun `전날 이전에 낸 주문은 끝난 것으로 보고 기다리지 않는다`() {
        Mockito.`when`(repository.findAll()).thenReturn(emptyList())
        service.sync(emptyList(), Instant.now())
        // 국내 지정가 주문은 장 마감으로 소멸하고, 끝남 확인은 오늘 주문만 찾아 영영 확인될 수 없다(원칙집 "주문 후 처리" ②).
        val yesterday =
            Trade(
                Market.KR,
                symbol,
                Side.BUY,
                1,
                BigDecimal("9000"),
                "Y1",
                "ok",
                executedAt = Instant.now().minus(java.time.Duration.ofDays(1)),
            )
        Mockito.`when`(tradeRepository.findFirstByMarketAndSymbolOrderByExecutedAtDesc(Market.KR, symbol)).thenReturn(yesterday)

        assertFalse(service.hasUnfinishedOrder(Market.KR, symbol))
    }
}

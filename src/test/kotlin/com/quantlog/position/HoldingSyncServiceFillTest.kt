package com.quantlog.position

import com.quantlog.broker.FillNotice
import com.quantlog.broker.Holding
import com.quantlog.broker.Market
import com.quantlog.broker.Side
import com.quantlog.watchlist.symbolStrategyServiceOf
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 체결통보로 보유 수량·평단을 바로 고치는 부분 ([HoldingSyncService.applyFill]). */
class HoldingSyncServiceFillTest {
    private val symbol = "005930"
    private val repository = Mockito.mock(AccountHoldingRepository::class.java)
    private val tradeRepository = Mockito.mock(TradeRepository::class.java)
    private val service = HoldingSyncService(repository, tradeRepository, symbolStrategyServiceOf())
    private val now = Instant.parse("2026-10-07T00:24:00Z")

    private fun row(
        quantity: Int,
        avg: String,
    ) = AccountHolding(Market.KR, symbol, quantity, BigDecimal(avg), BigDecimal("9000"))

    private fun notice(
        side: Side,
        quantity: Int,
        price: String,
        orderNo: String = "0000008775",
        orderQuantity: Int = quantity,
        fill: Boolean = true,
    ) = FillNotice(
        overseas = false,
        symbol = symbol,
        orderNo = orderNo,
        originalOrderNo = "",
        sellBuyCode = if (side == Side.BUY) "02" else "01",
        filledFlag = if (fill) "2" else "1",
        acceptFlag = if (fill) "2" else "1",
        refuseFlag = "0",
        filledQuantity = if (fill) BigDecimal(quantity) else null,
        filledPrice = if (fill) BigDecimal(price) else null,
        orderQuantity = BigDecimal(orderQuantity),
        orderPrice = BigDecimal(price),
        time = "092344",
    )

    @Test
    fun `보유가 없던 종목을 사면 체결가를 평단으로 새 행을 만든다`() {
        Mockito.`when`(repository.findByMarketAndSymbol(Market.KR, symbol)).thenReturn(null)

        assertNotNull(service.applyFill(notice(Side.BUY, 2, "277500"), now))

        val saved = ArgumentCaptor.forClass(AccountHolding::class.java)
        verify(repository).save(saved.capture() ?: row(0, "0"))
        assertEquals(2, saved.value.quantity)
        assertEquals(0, BigDecimal("277500").compareTo(saved.value.avgCost))
    }

    @Test
    fun `추가로 사면 수량 가중평균으로 평단을 다시 계산한다`() {
        val existing = row(2, "9000")
        Mockito.`when`(repository.findByMarketAndSymbol(Market.KR, symbol)).thenReturn(existing)

        service.applyFill(notice(Side.BUY, 3, "8800"), now)

        // (2×9000 + 3×8800) / 5 = 8880
        assertEquals(5, existing.quantity)
        assertEquals(0, BigDecimal("8880").compareTo(existing.avgCost))
        assertEquals(0, BigDecimal("8800").compareTo(existing.currentPrice))
    }

    @Test
    fun `일부 팔면 수량만 줄고 평단은 그대로이고 전량 팔면 행을 지운다`() {
        val existing = row(5, "8900")
        Mockito.`when`(repository.findByMarketAndSymbol(Market.KR, symbol)).thenReturn(existing)

        service.applyFill(notice(Side.SELL, 2, "9000", orderNo = "1"), now)
        assertEquals(3, existing.quantity)
        assertEquals(0, BigDecimal("8900").compareTo(existing.avgCost))
        verify(repository, never()).delete(any(AccountHolding::class.java))

        service.applyFill(notice(Side.SELL, 3, "9000", orderNo = "2"), now)
        verify(repository).delete(existing)
    }

    @Test
    fun `접수 통보와 해외 통보는 건드리지 않는다`() {
        assertNull(service.applyFill(notice(Side.BUY, 1, "277500", fill = false), now))
        assertNull(service.applyFill(notice(Side.BUY, 1, "277500").copy(overseas = true), now))

        verify(repository, never()).save(any(AccountHolding::class.java))
    }

    @Test
    fun `같은 통보가 다시 오거나 체결수량이 누적으로 와도 주문수량을 넘겨 더하지 않는다`() {
        val existing = row(10, "9000")
        Mockito.`when`(repository.findByMarketAndSymbol(Market.KR, symbol)).thenReturn(existing)

        // 주문수량 5: 3주 체결 뒤 같은 3주 통보가 다시 오면(또는 누적 3→5가 건별처럼 보이면) 남은 2주까지만 더한다
        service.applyFill(notice(Side.BUY, 3, "9000", orderQuantity = 5), now)
        service.applyFill(notice(Side.BUY, 3, "9000", orderQuantity = 5), now)
        assertEquals(15, existing.quantity)

        assertNull(service.applyFill(notice(Side.BUY, 5, "9000", orderQuantity = 5), now))
        assertEquals(15, existing.quantity)
    }

    @Test
    fun `체결통보를 반영한 종목은 그 전에 시작한 KIS 잔고 조회로 덮어쓰지 않는다`() {
        val existing = row(3, "9000")
        Mockito.`when`(repository.findByMarketAndSymbol(Market.KR, symbol)).thenReturn(existing)
        Mockito.`when`(repository.findAll()).thenReturn(listOf(existing))
        service.applyFill(notice(Side.BUY, 2, "9000"), now)
        assertEquals(5, existing.quantity)
        val staleKis = listOf(Holding(Market.KR, symbol, "삼성전자", BigDecimal(3), BigDecimal("9000"), BigDecimal("9000")))

        // 체결 반영(now) 이전에 시작한 조회(체결 전 잔고 3주)는 무시된다
        service.sync(staleKis, now.minusSeconds(1))
        assertEquals(5, existing.quantity)

        // 반영 뒤에 시작한 조회는 KIS 값이 정답이다
        service.sync(staleKis, now.plusSeconds(1))
        assertEquals(3, existing.quantity)
    }

    @Test
    fun `체결통보로 방금 만든 행을 체결 전 잔고 조회가 삭제하지 못한다`() {
        val created = row(1, "277500")
        Mockito.`when`(repository.findByMarketAndSymbol(Market.KR, symbol)).thenReturn(null)
        service.applyFill(notice(Side.BUY, 1, "277500"), now)
        Mockito.`when`(repository.findAll()).thenReturn(listOf(created))

        service.sync(emptyList(), now.minusSeconds(1))

        verify(repository, never()).delete(created)
    }

    @Test
    fun `주문수량만큼 다 반영해야 체결 완료이고 일부만 반영된 주문은 아직이다`() {
        Mockito.`when`(repository.findByMarketAndSymbol(Market.KR, symbol)).thenReturn(row(10, "9000"))

        service.applyFill(notice(Side.BUY, 3, "9000", orderNo = "P1", orderQuantity = 5), now)
        assertFalse(service.isOrderFilled("P1"))

        service.applyFill(notice(Side.BUY, 2, "9000", orderNo = "P1", orderQuantity = 5), now)
        assertTrue(service.isOrderFilled("P1"))
        assertFalse(service.isOrderFilled("없는 주문"))
    }

    @Test
    fun `주문을 체결통보로 다 반영했으면 잔고 동기화를 기다리지 않아도 된다`() {
        val synced = Instant.parse("2026-10-07T00:00:00Z")
        service.sync(emptyList(), synced)
        val trade = Trade(Market.KR, symbol, Side.BUY, 1, BigDecimal("278500"), "0000008775", "ok", executedAt = now)
        Mockito.`when`(tradeRepository.findFirstByMarketAndSymbolOrderByExecutedAtDesc(Market.KR, symbol)).thenReturn(trade)
        Mockito.`when`(repository.findByMarketAndSymbol(Market.KR, symbol)).thenReturn(null)

        assertTrue(service.hasUnsyncedTrade(Market.KR, symbol))

        service.applyFill(notice(Side.BUY, 1, "277500", orderNo = "0000008775"), now)

        assertFalse(service.hasUnsyncedTrade(Market.KR, symbol))
    }
}

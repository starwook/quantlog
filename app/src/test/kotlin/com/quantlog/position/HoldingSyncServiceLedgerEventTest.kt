package com.quantlog.position

import com.quantlog.broker.FillNotice
import com.quantlog.broker.Market
import com.quantlog.broker.Side
import com.quantlog.watchlist.symbolStrategyServiceOf
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 원장 줄을 보유 사본에 반영할 때 매매 기록·화면이 받을 이벤트([FillAppliedEvent])를 내는지. */
class HoldingSyncServiceLedgerEventTest {
    private val symbol = "233740"
    private val repository = Mockito.mock(AccountHoldingRepository::class.java)
    private val tradeRepository = Mockito.mock(TradeRepository::class.java)
    private val published = mutableListOf<Any>()
    private val ledger = InMemoryBrokerFills()
    private val service =
        HoldingSyncService(
            repository,
            tradeRepository,
            ledger,
            symbolStrategyServiceOf(),
            recordingPublisher(published),
        )
    private val now = Instant.parse("2026-10-08T00:27:51Z")

    private fun notice(
        side: Side,
        quantity: Int,
        price: String,
        orderNo: String,
    ) = FillNotice(
        symbol = symbol,
        orderNo = orderNo,
        originalOrderNo = "",
        sellBuyCode = if (side == Side.BUY) "02" else "01",
        filledFlag = "2",
        acceptFlag = "2",
        refuseFlag = "0",
        filledQuantity = BigDecimal(quantity),
        filledPrice = BigDecimal(price),
        orderQuantity = BigDecimal(quantity),
        orderPrice = BigDecimal(price),
        time = "092751",
    )

    private fun fills() = published.filterIsInstance<FillAppliedEvent>()

    @Test
    fun `매도 체결은 반영하기 전의 평단을 실어 이벤트를 낸다`() {
        val existing = AccountHolding(Market.KR, symbol, 100, BigDecimal("7835"), BigDecimal("7835"))
        Mockito.`when`(repository.findByMarketAndSymbol(Market.KR, symbol)).thenReturn(existing)

        // 앱이 1분 늦게 반영해도 체결 시각은 게이트웨이가 받은 시각이다.
        val receivedAt = now.minusSeconds(60)
        val notice = notice(Side.SELL, 100, "7860", "S1")
        val id = ledger.record(notice, receivedAt)
        service.applyFill(notice, id, receivedAt, now)

        val event = fills().single()
        assertEquals(Side.SELL, event.side)
        assertEquals(100, event.quantity)
        assertEquals(0, BigDecimal("7860").compareTo(event.price))
        assertEquals(0, BigDecimal("7835").compareTo(event.avgCostBefore))
        assertEquals("S1", event.orderNo)
        assertEquals(receivedAt, event.filledAt)
        assertEquals(id, event.noticeId)
    }

    @Test
    fun `보유 사본에 없던 종목의 매도 체결도 평단 null 로 알린다`() {
        Mockito.`when`(repository.findByMarketAndSymbol(Market.KR, symbol)).thenReturn(null)

        service.applyRecorded(ledger, notice(Side.SELL, 10, "7860", "S2"), now)

        assertNull(fills().single().avgCostBefore)
    }

    @Test
    fun `같은 통보가 다시 와도 한 번만 알린다`() {
        val existing = AccountHolding(Market.KR, symbol, 10, BigDecimal("7835"), BigDecimal("7835"))
        Mockito.`when`(repository.findByMarketAndSymbol(Market.KR, symbol)).thenReturn(existing)

        service.applyRecorded(ledger, notice(Side.BUY, 5, "7800", "B1"), now)
        service.applyRecorded(ledger, notice(Side.BUY, 5, "7800", "B1"), now)

        assertEquals(1, fills().size)
    }
}

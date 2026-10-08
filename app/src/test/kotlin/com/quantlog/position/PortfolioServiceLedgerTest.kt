package com.quantlog.position

import com.quantlog.broker.Market
import com.quantlog.broker.Side
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 매도 손익을 매매 기록의 체결 직전 평단([Trade.avgCostBefore])으로 확정하는 부분(docs/체결-원장-설계.md). */
class PortfolioServiceLedgerTest {
    private val tradeRepository = Mockito.mock(TradeRepository::class.java)
    private val holdingRepository = Mockito.mock(AccountHoldingRepository::class.java)
    private val ledger = InMemoryBrokerFills()
    private val service = PortfolioService(tradeRepository, holdingRepository, ledger)

    private val now = Instant.now()
    private var nextId = 1L

    private fun trade(
        side: Side,
        orderNo: String,
        quantity: Int,
        price: String,
        executedAt: Instant = now,
        avgCostBefore: String? = null,
        filledQuantity: Int? = null,
    ) = Trade(
        Market.KR, "233740", side, quantity,
        BigDecimal(
            price,
        ),
        orderNo, "ok", initialFilledPrice = BigDecimal(price), executedAt = executedAt,
        initialFilledQuantity = filledQuantity, initialAvgCostBefore = avgCostBefore?.let(::BigDecimal),
    )
        .also { Trade::class.java.getDeclaredField("id").apply { isAccessible = true }.set(it, nextId++) }

    private fun pnlOf(trade: Trade) = service.snapshot().realizedPnlByTradeId[trade.id]

    /** 게이트웨이 원장에 이 주문의 체결 줄을 쌓는다. */
    private fun recordFill(
        side: Side,
        orderNo: String,
        quantity: Int,
        price: String,
    ) = ledger.record(fillNotice(side, quantity, price, orderNo, symbol = "233740"))

    @Test
    fun `원장에 체결이 있는 매도는 매매 기록의 체결 직전 평단으로 손익을 확정한다`() {
        // 2026-10-08 사고: 옛 매수 100주 @7,894 와 FIFO 로 짝지어 +2,500원 익절이 -3,400원 손실로 표시됐다. 원장에 없는 기록은 손익에 끼지 않는다.
        val legacyBuy = trade(Side.BUY, "OLD", 100, "7894", now.minusSeconds(3600))
        val buy = trade(Side.BUY, "B1", 100, "7835", now.minusSeconds(1))
        val sell = trade(Side.SELL, "S1", 100, "7860", avgCostBefore = "7835", filledQuantity = 100)
        Mockito.`when`(tradeRepository.findAll()).thenReturn(listOf(legacyBuy, buy, sell))
        recordFill(Side.BUY, "B1", 100, "7835")
        recordFill(Side.SELL, "S1", 100, "7860")

        val pnl = pnlOf(sell)!!

        assertEquals(0, BigDecimal("2500").compareTo(pnl.amount))
        assertEquals(0, BigDecimal("7835").compareTo(pnl.avgBuyPrice))
        assertEquals(100, pnl.matchedQuantity)
    }

    @Test
    fun `일부만 체결된 뒤 취소된 매도도 체결된 수량만큼 손익에 넣는다`() {
        val sell = trade(Side.SELL, "S1", 47, "69730", avgCostBefore = "69655", filledQuantity = 10)
        sell.cancel()
        Mockito.`when`(tradeRepository.findAll()).thenReturn(listOf(sell))
        recordFill(Side.SELL, "S1", 10, "69730")

        val pnl = pnlOf(sell)!!

        assertEquals(0, BigDecimal("750").compareTo(pnl.amount))
        assertEquals(10, pnl.matchedQuantity)
    }

    @Test
    fun `원장에 체결이 있어도 체결 직전 평단을 모르는 매도는 손익을 지어내지 않는다`() {
        val buy = trade(Side.BUY, "B0", 100, "7000", now.minusSeconds(60))
        val sell = trade(Side.SELL, "S1", 100, "7860", filledQuantity = 100)
        Mockito.`when`(tradeRepository.findAll()).thenReturn(listOf(buy, sell))
        recordFill(Side.SELL, "S1", 100, "7860")

        assertNull(pnlOf(sell))
    }
}

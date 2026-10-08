package com.quantlog.position

import com.quantlog.broker.Market
import com.quantlog.broker.Side
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 체결 원장([TradeFill])으로 실현손익을 확정하는 부분 (docs/체결-원장-설계.md). */
class PortfolioServiceLedgerTest {
    private val tradeRepository = Mockito.mock(TradeRepository::class.java)
    private val tradeFillRepository = Mockito.mock(TradeFillRepository::class.java)
    private val holdingRepository = Mockito.mock(AccountHoldingRepository::class.java)
    private val service = PortfolioService(tradeRepository, holdingRepository, tradeFillRepository)

    private val now = Instant.now()
    private var nextId = 1L

    private fun trade(
        side: Side,
        orderNo: String,
        quantity: Int,
        price: String,
        executedAt: Instant = now,
    ) = Trade(
        Market.KR, "233740", side, quantity,
        BigDecimal(
            price,
        ),
        orderNo, "ok", initialFilledPrice = BigDecimal(price), executedAt = executedAt,
    )
        .also { Trade::class.java.getDeclaredField("id").apply { isAccessible = true }.set(it, nextId++) }

    private fun fill(
        side: Side,
        orderNo: String,
        quantity: Int,
        price: String,
        avgBefore: String?,
    ) = TradeFill(Market.KR, "233740", side, orderNo, quantity, BigDecimal(price), avgBefore?.let(::BigDecimal), now)

    private fun pnlOf(trade: Trade) = service.snapshot().realizedPnlByTradeId[trade.id]

    @Test
    fun `짝 없는 옛 매수 기록이 있어도 원장이 있는 매도는 체결 직전 평단으로 손익을 확정한다`() {
        // 2026-10-08 사고: 옛 매수 100주 @7,894 가 FIFO 에 남아 있어 +2,500원 익절이 -3,400원 손실로 표시됐다.
        val legacyBuy = trade(Side.BUY, "OLD", 100, "7894", now.minusSeconds(3600))
        val buy = trade(Side.BUY, "B1", 100, "7835", now.minusSeconds(1))
        val sell = trade(Side.SELL, "S1", 100, "7860")
        Mockito.`when`(tradeRepository.findAll()).thenReturn(listOf(legacyBuy, buy, sell))
        Mockito.`when`(tradeFillRepository.findAll()).thenReturn(
            listOf(fill(Side.BUY, "B1", 100, "7835", null), fill(Side.SELL, "S1", 100, "7860", "7835")),
        )

        val pnl = pnlOf(sell)!!

        assertEquals(0, BigDecimal("2500").compareTo(pnl.amount))
        assertEquals(0, BigDecimal("7835").compareTo(pnl.avgBuyPrice))
        assertEquals(100, pnl.matchedQuantity)
    }

    @Test
    fun `쪼개져 체결된 매도는 체결마다 그 시점 평단으로 합산한다`() {
        val sell = trade(Side.SELL, "S1", 100, "7860")
        Mockito.`when`(tradeRepository.findAll()).thenReturn(listOf(sell))
        Mockito.`when`(tradeFillRepository.findAll()).thenReturn(
            listOf(fill(Side.SELL, "S1", 44, "7860", "7835"), fill(Side.SELL, "S1", 56, "7870", "7835")),
        )

        val pnl = pnlOf(sell)!!

        // 44×25 + 56×35 = 1,100 + 1,960
        assertEquals(0, BigDecimal("3060").compareTo(pnl.amount))
        assertEquals(100, pnl.matchedQuantity)
    }

    @Test
    fun `일부만 체결된 뒤 취소된 매도도 체결된 몫은 손익에 넣는다`() {
        val sell = trade(Side.SELL, "S1", 47, "69720")
        sell.cancel()
        Mockito.`when`(tradeRepository.findAll()).thenReturn(listOf(sell))
        Mockito.`when`(tradeFillRepository.findAll()).thenReturn(listOf(fill(Side.SELL, "S1", 10, "69730", "69655")))

        val pnl = pnlOf(sell)!!

        assertEquals(0, BigDecimal("750").compareTo(pnl.amount))
        assertEquals(10, pnl.matchedQuantity)
    }

    @Test
    fun `평단을 모르는 체결만 있는 매도는 손익을 지어내지 않는다`() {
        val sell = trade(Side.SELL, "S1", 100, "7860")
        Mockito.`when`(tradeRepository.findAll()).thenReturn(listOf(sell))
        Mockito.`when`(tradeFillRepository.findAll()).thenReturn(listOf(fill(Side.SELL, "S1", 100, "7860", null)))

        assertNull(pnlOf(sell))
        assertTrue(service.snapshot().summaryByCurrency.getValue("KRW").sellWinToday == 0)
    }

    @Test
    fun `원장이 없는 옛 매도는 예전처럼 FIFO 로 계산한다`() {
        val buy = trade(Side.BUY, "OLD", 100, "7894", now.minusSeconds(60))
        val sell = trade(Side.SELL, "OLD2", 100, "7860")
        Mockito.`when`(tradeRepository.findAll()).thenReturn(listOf(buy, sell))

        assertEquals(0, BigDecimal("-3400").compareTo(pnlOf(sell)!!.amount))
    }
}

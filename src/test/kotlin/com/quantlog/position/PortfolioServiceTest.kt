package com.quantlog.position

import com.quantlog.broker.Market
import com.quantlog.broker.Side
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PortfolioServiceTest {
    private val tradeRepository = Mockito.mock(TradeRepository::class.java)
    private val accountHoldingRepository = Mockito.mock(AccountHoldingRepository::class.java)
    private val tradeFillRepository = Mockito.mock(TradeFillRepository::class.java)
    private val service = PortfolioService(tradeRepository, accountHoldingRepository, tradeFillRepository)

    private fun holdingRow(
        quantity: Int,
        avg: String,
        current: String,
    ) = AccountHolding(Market.KR, "005930", quantity, BigDecimal(avg), BigDecimal(current))

    @Test
    fun `보유 현황은 매매 기록이 비어 있어도 잔고 테이블 값을 그대로 보여준다`() {
        Mockito.`when`(tradeRepository.findAll()).thenReturn(emptyList())
        Mockito.`when`(accountHoldingRepository.findAll()).thenReturn(listOf(holdingRow(3, "70000", "71000")))

        val holding = service.snapshot().summaryByCurrency.getValue("KRW").holdings.single()

        assertEquals("005930", holding.symbol)
        assertEquals(3, holding.quantity)
        assertEquals(0, BigDecimal("70000").compareTo(holding.avgCost))
        assertEquals(0, BigDecimal("213000").compareTo(holding.value))
        assertEquals(0, BigDecimal("3000").compareTo(holding.unrealizedPnl))
    }

    @Test
    fun `매매 기록에 매수가 남아 있어도 잔고 테이블에 없으면 보유 중이 아니다`() {
        Mockito.`when`(tradeRepository.findAll()).thenReturn(listOf(trade(Market.KR, Side.BUY, Instant.now())))
        Mockito.`when`(accountHoldingRepository.findAll()).thenReturn(emptyList())

        assertTrue(service.snapshot().summaryByCurrency.values.all { it.holdings.isEmpty() })
    }

    private fun trade(
        market: Market,
        side: Side,
        executedAt: Instant,
        quantity: Int = 1,
        price: String = "100",
    ) = Trade(
        market = market,
        symbol = "005930",
        side = side,
        quantity = quantity,
        orderPrice = BigDecimal(price),
        orderNo = "1",
        message = "ok",
        initialFilledPrice = BigDecimal(price),
        executedAt = executedAt,
    )

    @Test
    fun `오늘 매수·매도 횟수를 통화별로 세고 어제 기록은 제외한다`() {
        val now = Instant.now()
        val yesterday = now.minus(2, ChronoUnit.DAYS)
        Mockito.`when`(tradeRepository.findAll()).thenReturn(
            listOf(
                trade(Market.KR, Side.BUY, now),
                trade(Market.KR, Side.BUY, now),
                trade(Market.KR, Side.SELL, now),
                trade(Market.KR, Side.BUY, yesterday),
            ),
        )

        val summaries = service.snapshot().summaryByCurrency

        assertEquals(2, summaries.getValue("KRW").buyCountToday)
        assertEquals(1, summaries.getValue("KRW").sellCountToday)
    }

    private fun tradeAt(
        market: Market,
        side: Side,
        price: String,
        executedAt: Instant,
        symbol: String = "005930",
    ) = Trade(
        market = market,
        symbol = symbol,
        side = side,
        quantity = 1,
        orderPrice = BigDecimal(price),
        orderNo = "1",
        message = "ok",
        initialFilledPrice = BigDecimal(price),
        executedAt = executedAt,
    )

    @Test
    fun `오늘 매도 승률은 이익은 성공, 손실과 본전은 실패로 센다`() {
        val now = Instant.now()
        val bought = now.minusSeconds(60)
        Mockito.`when`(tradeRepository.findAll()).thenReturn(
            listOf(
                tradeAt(Market.KR, Side.BUY, "100", bought),
                tradeAt(Market.KR, Side.BUY, "100", bought),
                tradeAt(Market.KR, Side.BUY, "100", bought),
                // 성공
                tradeAt(Market.KR, Side.SELL, "101", now),
                // 본전 → 실패
                tradeAt(Market.KR, Side.SELL, "100", now),
                // 손실 → 실패
                tradeAt(Market.KR, Side.SELL, "99", now),
            ),
        )

        val summary = service.snapshot().summaryByCurrency.getValue("KRW")

        assertEquals(1, summary.sellWinToday)
        assertEquals(2, summary.sellLossToday)
    }

    @Test
    fun `오늘 매도가 없으면 성공·실패 모두 0이다`() {
        val now = Instant.now()
        Mockito.`when`(tradeRepository.findAll()).thenReturn(listOf(tradeAt(Market.KR, Side.BUY, "100", now)))

        val summary = service.snapshot().summaryByCurrency.getValue("KRW")

        assertEquals(0, summary.sellWinToday)
        assertEquals(0, summary.sellLossToday)
    }

    @Test
    fun `어제 매도와 매수분 없는 매도는 승률에서 제외한다`() {
        val now = Instant.now()
        val twoDaysAgo = now.minus(2, ChronoUnit.DAYS)
        Mockito.`when`(tradeRepository.findAll()).thenReturn(
            listOf(
                tradeAt(Market.KR, Side.BUY, "100", twoDaysAgo),
                // 어제 매도
                tradeAt(Market.KR, Side.SELL, "110", twoDaysAgo.plusSeconds(60)),
                // 매칭할 매수분 없음
                tradeAt(Market.KR, Side.SELL, "110", now),
            ),
        )

        val summary = service.snapshot().summaryByCurrency.getValue("KRW")

        assertEquals(0, summary.sellWinToday)
        assertEquals(0, summary.sellLossToday)
    }

    @Test
    fun `체결가가 없는 미체결 주문은 손익과 횟수 계산에서 뺀다`() {
        val now = Instant.now()
        val pending =
            Trade(
                market = Market.KR,
                symbol = "005930",
                side = Side.BUY,
                quantity = 1,
                orderPrice = BigDecimal("100"),
                orderNo = "2",
                message = "ok",
                executedAt = now,
            )
        Mockito.`when`(tradeRepository.findAll()).thenReturn(listOf(trade(Market.KR, Side.BUY, now), pending))
        Mockito.`when`(accountHoldingRepository.findAll()).thenReturn(emptyList())

        val snapshot = service.snapshot()

        assertEquals(1, snapshot.summaryByCurrency.getValue("KRW").buyCountToday)
        assertEquals(2, snapshot.trades.size)
    }
}

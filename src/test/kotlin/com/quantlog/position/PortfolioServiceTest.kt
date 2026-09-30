package com.quantlog.position

import com.quantlog.broker.BrokerClient
import com.quantlog.broker.Holding
import com.quantlog.broker.Market
import com.quantlog.broker.Side
import com.quantlog.trading.HoldingSyncService
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PortfolioServiceTest {
    private val tradeRepository = Mockito.mock(TradeRepository::class.java)
    private val broker = Mockito.mock(BrokerClient::class.java)
    private val service = PortfolioService(tradeRepository, broker, Mockito.mock(HoldingSyncService::class.java))

    private val samsung =
        Holding(
            market = Market.KR,
            symbol = "005930",
            name = "삼성전자",
            quantity = BigDecimal(3),
            averagePrice = BigDecimal("70000"),
            currentPrice = BigDecimal("71000"),
        )

    @Test
    fun `화면용 스냅샷은 매매 기록이 비어 있어도 KIS 잔고를 보여준다`() {
        Mockito.`when`(tradeRepository.findAll()).thenReturn(emptyList())
        Mockito.`when`(broker.holdings(Market.KR)).thenReturn(listOf(samsung))

        val summary = service.accountSnapshot().summaryByCurrency.getValue("KRW")

        val holding = summary.holdings.single()
        assertEquals("005930", holding.symbol)
        assertEquals(3, holding.quantity)
        assertEquals(0, BigDecimal("213000").compareTo(holding.value))
        assertEquals(0, BigDecimal("3000").compareTo(holding.unrealizedPnl))
    }

    @Test
    fun `KIS 잔고 조회가 실패하면 매매 기록 기반 보유 현황으로 대체한다`() {
        Mockito.`when`(tradeRepository.findAll()).thenReturn(emptyList())
        Mockito.`when`(broker.holdings(Market.KR)).thenThrow(RuntimeException("KIS 오류"))

        val snapshot = service.accountSnapshot()

        assertTrue(snapshot.summaryByCurrency.values.all { it.holdings.isEmpty() })
    }

    @Test
    fun `매매 판단용 스냅샷은 KIS 잔고를 조회하지 않는다`() {
        Mockito.`when`(tradeRepository.findAll()).thenReturn(emptyList())

        service.snapshot()

        verify(broker, never()).holdings(Market.KR)
    }

    private fun trade(
        market: Market,
        side: Side,
        executedAt: Instant,
    ) = Trade(
        market = market,
        symbol = if (market == Market.KR) "005930" else "AAPL",
        side = side,
        quantity = 1,
        orderPrice = BigDecimal("100"),
        orderNo = "1",
        message = "ok",
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
                trade(Market.NASDAQ, Side.BUY, now),
            ),
        )

        val summaries = service.snapshot().summaryByCurrency

        assertEquals(2, summaries.getValue("KRW").buyCountToday)
        assertEquals(1, summaries.getValue("KRW").sellCountToday)
        assertEquals(1, summaries.getValue("USD").buyCountToday)
        assertEquals(0, summaries.getValue("USD").sellCountToday)
    }
}

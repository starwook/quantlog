package com.quantlog.position

import com.quantlog.broker.BrokerClient
import com.quantlog.broker.Holding
import com.quantlog.broker.Market
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PortfolioServiceTest {
    private val tradeRepository = Mockito.mock(TradeRepository::class.java)
    private val broker = Mockito.mock(BrokerClient::class.java)
    private val service = PortfolioService(tradeRepository, broker)

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
}

package com.quantlog.realized

import com.quantlog.broker.Market
import com.quantlog.broker.Side
import com.quantlog.position.AccountHoldingRepository
import com.quantlog.position.InMemoryBrokerFills
import com.quantlog.position.PortfolioService
import com.quantlog.position.Trade
import com.quantlog.position.TradeRepository
import com.quantlog.position.fillNotice
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.test.assertEquals

class RealizedPnlReportTest {
    private val ledger = InMemoryBrokerFills()
    private val kst = ZoneId.of("Asia/Seoul")
    private var nextId = 1L

    private fun trade(
        symbol: String,
        side: Side,
        date: LocalDate,
        hour: Int,
        price: String,
        avg: String? = null,
        quantity: Int = 10,
    ) = Trade(
        market = Market.KR,
        symbol = symbol,
        side = side,
        quantity = quantity,
        orderPrice = BigDecimal(price),
        orderNo = "${nextId++}",
        message = "ok",
        initialFilledPrice = BigDecimal(price),
        executedAt = date.atTime(hour, 0).atZone(kst).toInstant(),
        initialFilledQuantity = quantity,
        initialAvgCostBefore = avg?.let(::BigDecimal),
    ).also {
        Trade::class.java.getDeclaredField("id").apply { isAccessible = true }.set(it, nextId)
        ledger.record(fillNotice(side, quantity, price, it.orderNo, symbol = symbol))
    }

    private fun snapshot(vararg trades: Trade): com.quantlog.position.PortfolioSnapshot {
        val tradeRepository = Mockito.mock(TradeRepository::class.java)
        val holdings = Mockito.mock(AccountHoldingRepository::class.java)
        Mockito.`when`(tradeRepository.findAll()).thenReturn(trades.toList())
        return PortfolioService(tradeRepository, holdings, ledger).snapshot()
    }

    @Test
    fun `일별·종목별로 묶고 지난달 매도는 제외한다`() {
        val d1 = LocalDate.of(2026, 10, 6)
        val d2 = LocalDate.of(2026, 10, 7)
        val snapshot =
            snapshot(
                trade("A", Side.BUY, d1, 9, "100"),
                trade("B", Side.BUY, d1, 9, "200"),
                trade("A", Side.SELL, d1, 10, "110", avg = "100"),
                trade("B", Side.SELL, d2, 10, "190", avg = "200"),
                trade("A", Side.BUY, d2, 11, "100"),
                trade("A", Side.SELL, d2, 12, "120", avg = "100"),
                trade("C", Side.BUY, LocalDate.of(2026, 9, 30), 9, "100"),
                trade("C", Side.SELL, LocalDate.of(2026, 9, 30), 10, "150", avg = "100"),
            )

        val report = RealizedPnlReport.of(snapshot, YearMonth.of(2026, 10)).single()

        assertEquals("KRW", report.currency)
        assertEquals(0, BigDecimal(200).compareTo(report.total.amount))
        assertEquals(listOf(d2, d1), report.days.map { it.date })
        assertEquals(0, BigDecimal(100).compareTo(report.days.first().sum.amount))
        assertEquals(listOf("A", "B"), report.days.first().symbols.map { it.symbol })
        assertEquals(listOf("A", "B"), report.symbols.map { it.symbol })
        assertEquals(0, BigDecimal(300).compareTo(report.symbols.first().sum.amount))
        // A: 원가 1000+1000, 손익 +300 → 15%
        assertEquals(0, BigDecimal(15).compareTo(report.symbols.first().sum.percent))
    }

    @Test
    fun `해당 월에 매도가 없으면 비어 있다`() {
        assertEquals(emptyList(), RealizedPnlReport.of(snapshot(), YearMonth.of(2026, 10)))
    }
}

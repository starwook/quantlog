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
    private val ledger = InMemoryBrokerFills()
    private val service = PortfolioService(tradeRepository, accountHoldingRepository, ledger)

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
        Mockito.`when`(tradeRepository.findAll()).thenReturn(listOf(filled(Side.BUY, Instant.now())))
        Mockito.`when`(accountHoldingRepository.findAll()).thenReturn(emptyList())

        assertTrue(service.snapshot().summaryByCurrency.values.all { it.holdings.isEmpty() })
    }

    private var nextOrderNo = 1

    /** 체결이 원장(`kis_broker_fill`)에 반영된 주문. 매도는 [avg] 가 체결 직전 평단이다. */
    private fun filled(
        side: Side,
        executedAt: Instant,
        price: String = "100",
        avg: String? = null,
    ): Trade {
        val orderNo = "${nextOrderNo++}"
        ledger.record(fillNotice(side, 1, price, orderNo))
        return Trade(
            market = Market.KR,
            symbol = "005930",
            side = side,
            quantity = 1,
            orderPrice = BigDecimal(price),
            orderNo = orderNo,
            message = "ok",
            initialFilledPrice = BigDecimal(price),
            executedAt = executedAt,
            initialFilledQuantity = 1,
            initialAvgCostBefore = avg?.let(::BigDecimal),
        ).also { Trade::class.java.getDeclaredField("id").apply { isAccessible = true }.set(it, nextOrderNo.toLong()) }
    }

    @Test
    fun `오늘 매수·매도 횟수를 세고 어제 기록은 제외한다`() {
        val now = Instant.now()
        val yesterday = now.minus(2, ChronoUnit.DAYS)
        Mockito.`when`(tradeRepository.findAll()).thenReturn(
            listOf(
                filled(Side.BUY, now),
                filled(Side.BUY, now),
                filled(Side.SELL, now, avg = "100"),
                filled(Side.BUY, yesterday),
            ),
        )

        val summaries = service.snapshot().summaryByCurrency

        assertEquals(2, summaries.getValue("KRW").buyCountToday)
        assertEquals(1, summaries.getValue("KRW").sellCountToday)
    }

    @Test
    fun `오늘 매도 승률은 이익은 성공, 손실과 본전은 실패로 센다`() {
        val now = Instant.now()
        Mockito.`when`(tradeRepository.findAll()).thenReturn(
            listOf(
                // 성공
                filled(Side.SELL, now, "101", avg = "100"),
                // 본전 → 실패
                filled(Side.SELL, now, "100", avg = "100"),
                // 손실 → 실패
                filled(Side.SELL, now, "99", avg = "100"),
            ),
        )

        val summary = service.snapshot().summaryByCurrency.getValue("KRW")

        assertEquals(1, summary.sellWinToday)
        assertEquals(2, summary.sellLossToday)
    }

    @Test
    fun `오늘 매도가 없으면 성공·실패 모두 0이다`() {
        Mockito.`when`(tradeRepository.findAll()).thenReturn(listOf(filled(Side.BUY, Instant.now())))

        val summary = service.snapshot().summaryByCurrency.getValue("KRW")

        assertEquals(0, summary.sellWinToday)
        assertEquals(0, summary.sellLossToday)
    }

    @Test
    fun `어제 매도와 체결 직전 평단을 모르는 매도는 승률에서 제외한다`() {
        val now = Instant.now()
        Mockito.`when`(tradeRepository.findAll()).thenReturn(
            listOf(
                filled(Side.SELL, now.minus(2, ChronoUnit.DAYS), "110", avg = "100"),
                filled(Side.SELL, now, "110"),
            ),
        )

        val summary = service.snapshot().summaryByCurrency.getValue("KRW")

        assertEquals(0, summary.sellWinToday)
        assertEquals(0, summary.sellLossToday)
    }

    @Test
    fun `원장에 체결이 없는 주문은 손익과 횟수 계산에서 뺀다`() {
        val now = Instant.now()
        val pending =
            Trade(
                market = Market.KR,
                symbol = "005930",
                side = Side.BUY,
                quantity = 1,
                orderPrice = BigDecimal("100"),
                orderNo = "미체결",
                message = "ok",
                executedAt = now,
            )
        Mockito.`when`(tradeRepository.findAll()).thenReturn(listOf(filled(Side.BUY, now), pending))
        Mockito.`when`(accountHoldingRepository.findAll()).thenReturn(emptyList())

        val snapshot = service.snapshot()

        assertEquals(1, snapshot.summaryByCurrency.getValue("KRW").buyCountToday)
        assertEquals(2, snapshot.trades.size)
    }
}

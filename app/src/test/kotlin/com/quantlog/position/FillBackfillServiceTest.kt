package com.quantlog.position

import com.quantlog.broker.BrokerClient
import com.quantlog.broker.Market
import com.quantlog.broker.OrderFillTotal
import com.quantlog.broker.Side
import com.quantlog.sync.MismatchLog
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 게이트웨이 원장(`broker_notice`)이 체결통보를 놓쳤는지 KIS 체결내역과 대조하는 부분 ([FillBackfillService]). 원장에 쓰지는 않는다. */
class FillBackfillServiceTest {
    private val broker = Mockito.mock(BrokerClient::class.java)
    private val tradeRepository = Mockito.mock(TradeRepository::class.java)
    private val ledger = InMemoryBrokerFills()
    private val service = FillBackfillService(broker, tradeRepository, ledger)

    private val now = Instant.parse("2026-10-08T01:30:00Z")

    private fun buy(
        orderNo: String = "O1",
        quantity: Int = 44,
        executedAt: Instant = now.minusSeconds(600),
    ) = Trade(Market.KR, "226490", Side.BUY, quantity, BigDecimal("69595"), orderNo, "ok", executedAt = executedAt)

    private fun recordFill(
        quantity: Int,
        receivedAt: Instant = now.minusSeconds(500),
        orderNo: String = "O1",
    ) = ledger.record(fillNotice(Side.BUY, quantity, "69590", orderNo, symbol = "226490"), receivedAt)

    /** 원장이 돌기 시작한 시각을 정하는 기록(다른 주문의 것). 테스트 주문은 이보다 늦게 냈다. */
    private fun markLedgerStart() = recordFill(1, receivedAt = now.minusSeconds(7200), orderNo = "START")

    private fun given(
        trades: List<Trade>,
        totals: List<OrderFillTotal>,
    ) {
        Mockito.`when`(tradeRepository.findAll()).thenReturn(trades)
        Mockito.`when`(broker.todayOrderFills(Market.KR)).thenReturn(totals)
    }

    @Test
    fun `게이트웨이 원장이 KIS 보다 적으면 놓친 체결로 보고한다`() {
        markLedgerStart()
        recordFill(3)
        given(listOf(buy()), listOf(OrderFillTotal("O1", 44, BigDecimal("69590"))))

        MismatchLog().use { captured ->
            val result = service.backfill(now)

            assertEquals(1, result.missingOrders)
            val message = captured.messages.single()
            assertTrue("게이트웨이 원장 3주" in message && "KIS 44주" in message && "41주를 놓침" in message, message)
        }
    }

    @Test
    fun `같은 어긋남은 한 번만 보고한다`() {
        markLedgerStart()
        recordFill(3)
        given(listOf(buy()), listOf(OrderFillTotal("O1", 44, BigDecimal("69590"))))

        MismatchLog().use { captured ->
            service.backfill(now)
            service.backfill(now.plusSeconds(180))

            assertEquals(1, captured.messages.size)
        }
    }

    @Test
    fun `원장에 기록됐지만 앱이 아직 반영하지 않은 체결은 놓친 것이 아니다`() {
        // 앱 재시작 직후: 게이트웨이는 체결을 다 적었는데 앱의 반영 커서가 아직 그 앞에 있다(2026-10-08 리뷰에서 찾은 경합).
        markLedgerStart()
        val id = recordFill(44)
        ledger.projectedUpTo = id - 1
        given(listOf(buy()), listOf(OrderFillTotal("O1", 44, BigDecimal("69590"))))

        MismatchLog().use { captured ->
            val result = service.backfill(now)

            assertEquals(0, result.missingOrders)
            assertTrue(captured.messages.isEmpty(), captured.messages.toString())
        }
    }

    @Test
    fun `방금 체결이 기록된 주문은 통보가 오는 중일 수 있어 건너뛴다`() {
        markLedgerStart()
        recordFill(3, receivedAt = now.minusSeconds(5))
        given(listOf(buy()), listOf(OrderFillTotal("O1", 44, BigDecimal("69590"))))

        assertEquals(0, service.backfill(now).missingOrders)
    }

    @Test
    fun `이미 주문수량만큼 원장에 쌓인 주문뿐이면 KIS 를 부르지 않는다`() {
        markLedgerStart()
        recordFill(5)
        given(listOf(buy(quantity = 5)), emptyList())

        val result = service.backfill(now)

        assertEquals(false, result.queriedKis)
        verify(broker, never()).todayOrderFills(Market.KR)
    }

    @Test
    fun `원장이 KIS 보다 많으면 고치지 않고 어긋난 주문으로 센다`() {
        markLedgerStart()
        recordFill(8)
        given(listOf(buy(quantity = 10)), listOf(OrderFillTotal("O1", 5, BigDecimal("69590"))))

        val result = service.backfill(now)

        assertEquals(1, result.mismatchedOrders)
        assertEquals(0, result.missingOrders)
    }

    @Test
    fun `KIS 조회가 실패하면 이번 회차를 건너뛴다`() {
        markLedgerStart()
        Mockito.`when`(tradeRepository.findAll()).thenReturn(listOf(buy()))
        Mockito.`when`(broker.todayOrderFills(Market.KR)).thenThrow(IllegalStateException("KIS 지연"))

        val result = service.backfill(now)

        assertEquals(0, result.missingOrders)
        assertEquals(true, result.queriedKis)
    }

    @Test
    fun `어제 주문은 보지 않는다`() {
        markLedgerStart()
        given(listOf(buy(executedAt = now.minusSeconds(86_400 * 2))), listOf(OrderFillTotal("O1", 44, BigDecimal("1"))))

        service.backfill(now)

        verify(broker, never()).todayOrderFills(Market.KR)
    }

    @Test
    fun `원장이 돌기 전에 낸 옛 주문은 보지 않는다`() {
        markLedgerStart()
        given(listOf(buy(executedAt = now.minusSeconds(10_000))), listOf(OrderFillTotal("O1", 44, BigDecimal("69590"))))

        assertEquals(0, service.backfill(now).missingOrders)
        verify(broker, never()).todayOrderFills(Market.KR)
    }

    @Test
    fun `원장이 비어 있으면 시작 시각을 몰라 보지 않는다`() {
        Mockito.`when`(tradeRepository.findAll()).thenReturn(listOf(buy()))

        assertEquals(0, service.backfill(now).missingOrders)
        verify(broker, never()).todayOrderFills(Market.KR)
    }
}

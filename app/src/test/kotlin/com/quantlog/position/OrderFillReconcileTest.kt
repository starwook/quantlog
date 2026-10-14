package com.quantlog.position

import com.quantlog.broker.Market
import com.quantlog.broker.OrderFillTotal
import com.quantlog.broker.Side
import com.quantlog.sync.SyncMismatchReporter
import com.quantlog.watchlist.symbolStrategyServiceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 체결통보가 빠졌을 때 한투 체결조회로 채우는 부분([HoldingFillApplier.applyInquiry]). 주문의 체결은 체결통보 합계와 체결조회 중 수량이 큰 쪽이라
 * 무엇이 먼저 오든, 같은 체결을 양쪽에서 봐도 두 번 반영하지 않는다(2026-10-14 233740: 웹소켓이 끊긴 사이의 100주 통보가 끝내 안 왔다).
 */
class OrderFillReconcileTest {
    private val symbol = "233740"
    private val tradeRepository = Mockito.mock(TradeRepository::class.java)
    private val ledger = InMemoryBrokerFills()
    private val holdings = mutableMapOf<String, AccountHolding>()
    private val repository =
        Mockito.mock(AccountHoldingRepository::class.java).also { repo ->
            Mockito.`when`(repo.findByMarketAndSymbol(Market.KR, symbol)).thenAnswer { holdings[symbol] }
            Mockito.`when`(repo.save(any(AccountHolding::class.java))).thenAnswer {
                (it.arguments[0] as AccountHolding).also {
                        h ->
                    holdings[h.symbol] = h
                }
            }
            Mockito.doAnswer {
                holdings.remove(
                    (it.arguments[0] as AccountHolding).symbol,
                )
            }.`when`(repo).delete(any(AccountHolding::class.java))
        }
    private val events = mutableListOf<Any>()
    private val service = HoldingSyncFixture(repository, tradeRepository, ledger, symbolStrategyServiceOf(), recordingPublisher(events))
    private val now = Instant.parse("2026-10-14T02:00:10Z")
    private val reports = mutableListOf<String>()

    init {
        SyncMismatchReporter.sink = { subject, detail -> reports += "$subject: $detail" }
    }

    @AfterEach
    fun tearDown() {
        SyncMismatchReporter.sink = null
    }

    private val trade = Trade(Market.KR, symbol, Side.BUY, 100, BigDecimal("6760"), "0000026674", "ok", executedAt = now)

    private fun notice(quantity: Int) = fillNotice(Side.BUY, quantity, "6745", "0000026674", symbol = symbol, orderQuantity = 100)

    private fun inquiry(
        quantity: Int,
        remaining: Int = 100 - quantity,
    ) = OrderFillTotal("0000026674", quantity, BigDecimal("6745"), BigDecimal(6745 * quantity), remaining)

    private fun held() = holdings[symbol]?.quantity ?: 0

    init {
        Mockito.`when`(tradeRepository.findAllByMarketAndOrderNo(Market.KR, "0000026674")).thenReturn(listOf(trade))
    }

    @Test
    fun `통보가 빠진 몫을 체결조회로 채우고 늦게 온 통보는 다시 더하지 않는다`() {
        // 끊긴 사이 30주 체결(통보 유실) → 재연결 뒤 50주 통보
        service.applyRecorded(ledger, notice(50), now)
        assertEquals(50, held())

        // 조회 응답: 마지막 20주까지 포함해 100주
        val change = service.fillApplier.applyInquiry(trade, inquiry(100), now)
        assertTrue(change != null)
        assertEquals(100, held())
        assertEquals(0, BigDecimal("6745").compareTo(holdings[symbol]!!.avgCost))
        assertEquals(1, reports.size)
        assertTrue(reports.single().contains("체결통보 50주"))
        assertTrue(service.isOrderClosed("0000026674"))

        // 마지막 20주 통보가 늦게 온다 — 조회가 이미 셌다.
        assertNull(service.applyRecorded(ledger, notice(20), now))
        assertEquals(100, held())
        assertEquals(70, service.fills.of("0000026674")!!.noticeQuantity)
    }

    @Test
    fun `조회가 통보보다 적거나 같으면 보유를 건드리지 않고 보고도 하지 않는다`() {
        service.applyRecorded(ledger, notice(40), now)

        assertNull(service.fillApplier.applyInquiry(trade, inquiry(30), now))

        assertEquals(40, held())
        assertTrue(reports.isEmpty())
        assertFalse(service.isOrderClosed("0000026674"))
    }

    @Test
    fun `내용이 똑같은 통보도 서로 다른 체결이라 모두 더한다`() {
        repeat(4) { service.applyRecorded(ledger, notice(25), now) }

        assertEquals(100, held())
        assertTrue(service.isOrderFilled("0000026674"))
    }

    @Test
    fun `남은 수량 0 으로 조회되면 덜 체결됐어도 끝난 주문이다`() {
        service.applyRecorded(ledger, notice(10), now)

        service.fillApplier.applyInquiry(trade, inquiry(10, remaining = 0), now)

        assertTrue(service.isOrderClosed("0000026674"))
        assertFalse(service.isOrderFilled("0000026674"))
    }

    @Test
    fun `끝난 주문인데 통보가 조회보다 많으면 고치지 않고 보고만 한다`() {
        service.applyRecorded(ledger, notice(60), now)

        service.fillApplier.applyInquiry(trade, inquiry(50, remaining = 0), now)

        assertEquals(60, held())
        assertTrue(reports.single().contains("통보가 더 많다"))
    }

    @Test
    fun `이 기능을 장중에 처음 배포한 날에도 원장으로 합계를 먼저 채워 이미 보유에 든 체결을 다시 얹지 않는다`() {
        // 배포 전: 통보 100주가 이미 보유에 반영돼 있고(옛 코드), order_fill 은 비어 있다.
        holdings[symbol] = AccountHolding(Market.KR, symbol, 100, BigDecimal("6745"), BigDecimal("6745"))
        ledger.record(notice(100), Instant.now())

        service.fillApplier.rebuildToday()
        assertNull(service.fillApplier.applyInquiry(trade, inquiry(100, remaining = 0), now))

        assertEquals(100, held())
        assertTrue(reports.isEmpty())
    }
}

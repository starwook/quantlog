package com.quantlog.position

import com.quantlog.broker.FillNotice
import com.quantlog.broker.Holding
import com.quantlog.broker.Market
import com.quantlog.sync.MismatchLog
import com.quantlog.watchlist.symbolStrategyServiceOf
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 증권사 잔고·체결통보와 보유 사본(DB)이 다를 때마다 `[동기화 불일치]` 오류가 남는지, 그래도 동기화는 이어지는지. */
class HoldingSyncMismatchTest {
    private val symbol = "005930"
    private val repository = Mockito.mock(AccountHoldingRepository::class.java)
    private val tradeRepository = Mockito.mock(TradeRepository::class.java)
    private val ledger = InMemoryBrokerFills()
    private val service =
        HoldingSyncService(repository, tradeRepository, ledger, symbolStrategyServiceOf(), recordingPublisher())
    private val now = Instant.parse("2026-10-08T01:00:00Z")

    private fun kis(
        quantity: Int,
        average: String,
    ) = Holding(Market.KR, symbol, "삼성전자", BigDecimal(quantity), BigDecimal(average), BigDecimal("70000"))

    private fun row(
        quantity: Int,
        avg: String,
    ) = AccountHolding(Market.KR, symbol, quantity, BigDecimal(avg), BigDecimal("70000"))

    private fun sellNotice(quantity: Int) =
        FillNotice(
            symbol = symbol,
            orderNo = "0000001234",
            originalOrderNo = "",
            sellBuyCode = "01",
            filledFlag = "2",
            acceptFlag = "2",
            refuseFlag = "0",
            filledQuantity = BigDecimal(quantity),
            filledPrice = BigDecimal("71000"),
            orderQuantity = BigDecimal(quantity),
            orderPrice = BigDecimal("71000"),
            time = "100000",
        )

    @Test
    fun `증권사 잔고에만 있는 종목은 보고하고 DB 에 추가한다`() {
        Mockito.`when`(repository.findAll()).thenReturn(emptyList())

        MismatchLog().use { captured ->
            val changes = service.sync(listOf(kis(5, "70000")), now)

            assertEquals(1, changes.size)
            val message = captured.messages.single()
            assertTrue("잔고 동기화" in message && symbol in message && "DB=보유 없음" in message && "증권사=5주" in message, message)
        }
        Mockito.verify(repository).save(Mockito.any(AccountHolding::class.java) ?: row(0, "0"))
    }

    @Test
    fun `수량이 다르면 보고하되 동기화는 이어져 DB 가 증권사 값으로 고쳐진다`() {
        val existing = row(2, "70000")
        Mockito.`when`(repository.findAll()).thenReturn(listOf(existing))

        MismatchLog().use { captured ->
            service.sync(listOf(kis(5, "70000")), now)

            val message = captured.messages.single()
            assertTrue("DB=2주" in message && "증권사=5주" in message, message)
        }
        assertEquals(5, existing.quantity)
    }

    @Test
    fun `증권사 잔고에서 사라진 종목은 보고하고 삭제한다`() {
        val existing = row(2, "70000")
        Mockito.`when`(repository.findAll()).thenReturn(listOf(existing))

        MismatchLog().use { captured ->
            service.sync(emptyList(), now)

            val message = captured.messages.single()
            assertTrue("DB=2주" in message && "증권사=보유 없음" in message, message)
        }
        Mockito.verify(repository).delete(existing)
    }

    @Test
    fun `수량과 평단이 같으면 보고하지 않는다`() {
        Mockito.`when`(repository.findAll()).thenReturn(listOf(row(5, "70000")))

        MismatchLog().use { captured ->
            service.sync(listOf(kis(5, "70000")), now)

            assertTrue(captured.messages.isEmpty(), captured.messages.toString())
        }
    }

    @Test
    fun `평단의 미세한 차이는 보고하지 않지만 크게 다르면 보고한다`() {
        MismatchLog().use { captured ->
            // 70000 vs 70005 = 약 0.007% — 허용 범위(0.01%) 안
            Mockito.`when`(repository.findAll()).thenReturn(listOf(row(5, "70000")))
            service.sync(listOf(kis(5, "70005")), now)
            assertTrue(captured.messages.isEmpty(), captured.messages.toString())

            // 70000 vs 71000 = 약 1.4% — 허용 범위 밖
            Mockito.`when`(repository.findAll()).thenReturn(listOf(row(5, "70000")))
            service.sync(listOf(kis(5, "71000")), now)
            val message = captured.messages.single()
            assertTrue("평단 70000" in message && "평단 71000" in message, message)
        }
    }

    @Test
    fun `보유가 없는 종목의 매도 체결통보는 보고하고 건너뛴다`() {
        Mockito.`when`(repository.findByMarketAndSymbol(Market.KR, symbol)).thenReturn(null)

        MismatchLog().use { captured ->
            val change = service.applyRecorded(ledger, sellNotice(3), now)

            assertEquals(null, change)
            val message = captured.messages.single()
            assertTrue("체결통보 반영" in message && "DB=보유 없음" in message && "3주 매도 체결" in message, message)
        }
    }

    @Test
    fun `보유보다 많이 팔았다는 체결통보는 보고하고 보유를 0주로 맞춘다`() {
        val existing = row(2, "70000")
        Mockito.`when`(repository.findByMarketAndSymbol(Market.KR, symbol)).thenReturn(existing)

        MismatchLog().use { captured ->
            service.applyRecorded(ledger, sellNotice(5), now)

            val message = captured.messages.single()
            assertTrue("DB=2주 보유" in message && "5주 매도 체결" in message, message)
        }
        Mockito.verify(repository).delete(existing)
    }

    @Test
    fun `정상 매도 체결통보는 보고하지 않는다`() {
        Mockito.`when`(repository.findByMarketAndSymbol(Market.KR, symbol)).thenReturn(row(5, "70000"))

        MismatchLog().use { captured ->
            service.applyRecorded(ledger, sellNotice(3), now)

            assertTrue(captured.messages.isEmpty(), captured.messages.toString())
        }
    }
}

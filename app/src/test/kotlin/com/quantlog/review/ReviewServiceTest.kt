package com.quantlog.review

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.quantlog.broker.LatestPrices
import com.quantlog.broker.Market
import com.quantlog.broker.Side
import com.quantlog.playbook.Playbook
import com.quantlog.playbook.PlaybookProperties
import com.quantlog.position.AccountHoldingRepository
import com.quantlog.position.HoldingValuation
import com.quantlog.position.InMemoryBrokerFills
import com.quantlog.position.InMemoryOrderFillStates
import com.quantlog.position.PortfolioService
import com.quantlog.position.Trade
import com.quantlog.position.TradeRepository
import com.quantlog.position.fillNotice
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReviewServiceTest {
    @TempDir
    lateinit var dir: Path

    private val kst = ZoneId.of("Asia/Seoul")

    // 2026-10-12 15:40 KST
    private val clock = Clock.fixed(Instant.parse("2026-10-12T06:40:00Z"), kst)
    private val ledger = InMemoryBrokerFills()

    private class FakeLlm(private val answer: String) : LlmClient {
        var user = ""

        override fun complete(
            system: String,
            user: String,
        ): String {
            this.user = user
            return answer
        }
    }

    private fun trade(
        side: Side,
        hour: Int,
        price: String,
        avg: String? = null,
    ) = Trade(
        market = Market.KR,
        symbol = "005930",
        side = side,
        quantity = 10,
        orderPrice = BigDecimal(price),
        orderNo = "$hour",
        message = "ok",
        reason = if (side == Side.BUY) "주기 재매수" else "익절 +0.5%",
        initialFilledPrice = BigDecimal(price),
        executedAt = clock.instant().atZone(kst).toLocalDate().atTime(hour, 0).atZone(kst).toInstant(),
        initialFilledQuantity = 10,
        initialAvgCostBefore = avg?.let(::BigDecimal),
    ).also {
        Trade::class.java.getDeclaredField("id").apply { isAccessible = true }.set(it, hour.toLong())
        ledger.record(fillNotice(side, 10, price, it.orderNo, symbol = "005930"), it.executedAt)
    }

    // 저장소 대신 넘겨받은 것을 모아 둔다. 저장소가 id 를 붙여 주는 것처럼 1부터 번호를 매긴다.
    private class RecordingArchive(clock: Clock) : ReviewArchive(
        Mockito.mock(ReviewJournalRepository::class.java),
        Mockito.mock(ReviewProposalRepository::class.java),
        clock,
    ) {
        val journals = mutableListOf<Triple<LocalDate, String, String>>()
        val proposals = mutableListOf<Triple<LocalDate, String, String>>()

        private fun <T : Any> T.withId(id: Long): T = also { it.javaClass.getDeclaredField("id").apply { isAccessible = true }.set(it, id) }

        override fun saveJournal(
            day: LocalDate,
            title: String,
            body: String,
        ): ReviewJournal {
            journals += Triple(day, title, body)
            return ReviewJournal(day, title, body, Instant.EPOCH).withId(journals.size.toLong())
        }

        override fun saveProposal(
            day: LocalDate,
            title: String,
            body: String,
        ): ReviewProposal {
            proposals += Triple(day, title, body)
            return ReviewProposal(day, title, body, createdAt = Instant.EPOCH).withId(proposals.size.toLong())
        }
    }

    private lateinit var archive: RecordingArchive

    private fun service(llm: LlmClient): ReviewService {
        archive = RecordingArchive(clock)
        Files.writeString(dir.resolve("principles.md"), "# 원칙\n- 롱만 한다")
        val trades = Mockito.mock(TradeRepository::class.java)
        Mockito.`when`(trades.findAll()).thenReturn(listOf(trade(Side.BUY, 10, "100000"), trade(Side.SELL, 11, "100500", avg = "100000")))
        val portfolio =
            PortfolioService(
                trades,
                Mockito.mock(AccountHoldingRepository::class.java),
                ledger,
                InMemoryOrderFillStates().states,
                HoldingValuation(LatestPrices()),
            )
        return ReviewService(
            portfolio,
            Playbook(PlaybookProperties(dir.toString())),
            archive,
            llm,
            ReviewProperties(),
            clock,
            jacksonObjectMapper(),
        )
    }

    @Test
    fun `코드가 계산한 사실과 원칙을 Claude 에게 주고 일지와 제안을 남긴다`() {
        val llm =
            FakeLlm(
                """```json
                {"journal": "익절이 의도대로 나갔다.", "proposals": [{"title": "익절 폭 검토", "body": "표본 부족, 관찰만"}]}
                ```""",
            )

        val result = service(llm).run()

        assertTrue(llm.user.contains("롱만 한다"))
        assertTrue(llm.user.contains("주기 재매수"))
        assertTrue(llm.user.contains("실현손익 5000 (0.50%)"))
        // 리뷰 대상일(2026-10-12 15:40 → 그날)로 일지와 제안이 저장된다.
        assertEquals(listOf(Triple(LocalDate.of(2026, 10, 12), "일일 리뷰", "익절이 의도대로 나갔다.")), archive.journals)
        assertEquals(listOf(Triple(LocalDate.of(2026, 10, 12), "익절 폭 검토", "표본 부족, 관찰만")), archive.proposals)
        assertEquals(1, result.proposalIds.size)
    }

    @Test
    fun `응답을 JSON 으로 못 읽으면 원문을 일지에 남기고 제안은 만들지 않는다`() {
        val result = service(FakeLlm("그냥 말로 답함")).run()

        assertTrue(archive.journals.single().third.contains("그냥 말로 답함"))
        assertEquals(0, result.proposalIds.size)
    }

    @Test
    fun `원칙 파일은 리뷰가 바꾸지 않는다`() {
        service(FakeLlm("""{"journal": "x", "proposals": []}""")).run()

        assertEquals("# 원칙\n- 롱만 한다", Files.readString(dir.resolve("principles.md")))
    }

    @Test
    fun `리뷰 대상일은 마지막으로 장이 마감된 평일이다`() {
        fun day(time: String) = reviewDay(ZonedDateTime.parse("$time[Asia/Seoul]"))

        assertEquals(LocalDate.of(2026, 10, 12), day("2026-10-13T06:00:00+09:00")) // 화 새벽 → 월
        assertEquals(LocalDate.of(2026, 10, 13), day("2026-10-13T15:31:00+09:00")) // 화 마감 직후 → 화
        assertEquals(LocalDate.of(2026, 10, 12), day("2026-10-13T15:30:00+09:00")) // 마감 전 → 월
        assertEquals(LocalDate.of(2026, 10, 9), day("2026-10-12T06:00:00+09:00")) // 월 새벽 → 금
        assertEquals(LocalDate.of(2026, 10, 9), day("2026-10-11T12:00:00+09:00")) // 일 → 금
    }
}

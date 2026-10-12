package com.quantlog.review

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.quantlog.broker.Market
import com.quantlog.broker.Side
import com.quantlog.playbook.Playbook
import com.quantlog.playbook.PlaybookProperties
import com.quantlog.position.AccountHoldingRepository
import com.quantlog.position.InMemoryBrokerFills
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
import java.time.ZoneId
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

    private fun service(llm: LlmClient): ReviewService {
        Files.writeString(dir.resolve("principles.md"), "# 원칙\n- 롱만 한다")
        val trades = Mockito.mock(TradeRepository::class.java)
        Mockito.`when`(trades.findAll()).thenReturn(listOf(trade(Side.BUY, 10, "100000"), trade(Side.SELL, 11, "100500", avg = "100000")))
        val portfolio = PortfolioService(trades, Mockito.mock(AccountHoldingRepository::class.java), ledger)
        return ReviewService(
            portfolio,
            Playbook(PlaybookProperties(dir.toString()), clock),
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
        assertTrue(Files.readString(result.journal).contains("익절이 의도대로 나갔다."))
        assertEquals(1, result.proposals.size)
        assertTrue(Files.readString(result.proposals.single()).contains("익절 폭 검토"))
    }

    @Test
    fun `응답을 JSON 으로 못 읽으면 원문을 일지에 남기고 제안은 만들지 않는다`() {
        val result = service(FakeLlm("그냥 말로 답함")).run()

        assertTrue(Files.readString(result.journal).contains("그냥 말로 답함"))
        assertEquals(0, result.proposals.size)
    }

    @Test
    fun `원칙 파일은 리뷰가 바꾸지 않는다`() {
        service(FakeLlm("""{"journal": "x", "proposals": []}""")).run()

        assertEquals("# 원칙\n- 롱만 한다", Files.readString(dir.resolve("principles.md")))
    }
}

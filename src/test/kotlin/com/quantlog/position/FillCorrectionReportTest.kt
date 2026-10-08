package com.quantlog.position

import com.quantlog.broker.Market
import com.quantlog.broker.OrderStatus
import com.quantlog.broker.Side
import com.quantlog.sync.MismatchLog
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 증권사 체결 조회가 "체결"인데 DB 에 체결 정보가 없어서 고친 경우의 불일치 보고 ([reportFillCorrection]). */
class FillCorrectionReportTest {
    private val orderedAt = Instant.parse("2026-10-08T01:00:00Z")

    private fun trade() = Trade(Market.KR, "005930", Side.BUY, 2, BigDecimal("71000"), "0000005555", "ok", executedAt = orderedAt)

    @Test
    fun `주문 직후 30초 안에는 체결통보가 아직 닿는 중이라 보고하지 않는다`() {
        MismatchLog().use { captured ->
            val trade = trade()
            reportFillCorrection(trade, trade.fillStateText(), OrderStatus.Filled(BigDecimal("70500")), orderedAt.plusSeconds(10))

            assertTrue(captured.messages.isEmpty(), captured.messages.toString())
        }
    }

    @Test
    fun `30초가 지났는데도 DB 에 체결가가 없었으면 보고한다`() {
        MismatchLog().use { captured ->
            val trade = trade()
            reportFillCorrection(trade, trade.fillStateText(), OrderStatus.Filled(BigDecimal("70500")), orderedAt.plusSeconds(60))

            val message = captured.messages.single()
            assertTrue("체결 조회" in message && "0000005555" in message && "DB=체결가 없음" in message && "70500" in message, message)
        }
    }

    @Test
    fun `체결 정보 상태 문구`() {
        val trade = trade()
        assertEquals("체결가 없음", trade.fillStateText())

        trade.applyFill(BigDecimal("70500"), 1)
        assertEquals("일부 체결 1/2주", trade.fillStateText())

        trade.applyFill(BigDecimal("70500"), 2)
        assertEquals("체결 70500", trade.fillStateText())
    }
}

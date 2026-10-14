package com.quantlog.order

import com.quantlog.broker.Market
import com.quantlog.broker.Side
import com.quantlog.position.OrderFill
import com.quantlog.position.OrderFillState
import com.quantlog.position.RealizedPnl
import com.quantlog.position.Trade
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 체결 내역 화면 한 줄([OrderFillLiveView]) — 주문 하나가 한 줄이고, 펼치면 건별 체결과 체결조회로 채운 몫이 보인다. */
class OrderFillLiveViewTest {
    private val at = Instant.parse("2026-10-14T02:00:05Z")
    private val day = LocalDate.parse("2026-10-14")

    private fun state(
        side: Side,
        noticed: Int,
        noticedAmount: Long,
        inquired: Int = 0,
        inquiredAmount: Long = 0,
        settled: Boolean = false,
    ) = OrderFillState(day, Market.KR, "0000026674", "233740", side).also {
        it.addNotice(noticed, BigDecimal(noticedAmount), at)
        if (inquired > 0 || settled) it.recordInquiry(inquired, BigDecimal(inquiredAmount), settled, at)
    }

    private fun ledger(vararg quantities: Int) =
        quantities.mapIndexed {
                i,
                q,
            ->
            OrderFill(i + 1L, Market.KR, "233740", Side.BUY, "0000026674", q, BigDecimal("6745"), at.plusSeconds(i.toLong()))
        }

    private fun trade(quantity: Int) =
        Trade(
            Market.KR,
            "233740",
            Side.BUY,
            quantity,
            BigDecimal("6760"),
            "0000026674",
            "ok",
            executedAt = at,
        )

    @Test
    fun `통보가 빠진 몫은 펼친 목록에 체결조회로 채운 줄로 보이고 수량은 큰 쪽이다`() {
        val view = OrderFillLiveView.of(state(Side.BUY, 50, 337_250, 100, 674_500), ledger(25, 25), trade(100), null, "KODEX")

        assertEquals(100, view.quantity)
        assertEquals("6,745", view.priceText)
        assertEquals("체결", view.stateText)
        assertEquals(3, view.details.size)
        assertEquals("50주 @ 평균 6,745 — 체결통보가 빠져 체결조회로 채움", view.details.last().text)
        assertEquals("시각 모름", view.details.last().timeText)
        assertNull(view.pnlText)
    }

    @Test
    fun `일부만 체결되면 진행 상태를 보이고 끝났으면 일부 체결 후 취소로 보인다`() {
        assertEquals("일부 체결 30/100", OrderFillLiveView.of(state(Side.BUY, 30, 202_350), ledger(30), trade(100), null, "K").stateText)
        assertEquals(
            "일부 체결 후 취소 30/100",
            OrderFillLiveView.of(state(Side.BUY, 30, 202_350, 30, 202_350, settled = true), ledger(30), trade(100), null, "K").stateText,
        )
    }

    @Test
    fun `매도 줄은 확정된 손익과 근거를 보여 주고 같은 주문은 늘 같은 키다`() {
        val pnl = RealizedPnl(BigDecimal("25000"), BigDecimal("2.54"), BigDecimal("9850"), 100)
        val sold = state(Side.SELL, 100, 1_010_000)

        val view = OrderFillLiveView.of(sold, ledger(40, 60), trade(100), pnl, "KODEX")

        assertEquals("pos", view.pnlCss)
        assertEquals("매수 원가 평균 9,850 → 매도 평균 10,100 · 100주", view.pnlDetailText)
        assertEquals(view.key, OrderFillLiveView.of(sold, ledger(40), trade(100), pnl, "KODEX").key)
    }
}

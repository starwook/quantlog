package com.quantlog.order

import com.quantlog.broker.Market
import com.quantlog.broker.Side
import com.quantlog.position.OrderFill
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 체결 내역 화면 한 줄([FillLiveView]) — 원장(`kis_broker_fill`) 1줄이 한 줄이고, 매도 줄에만 그 체결의 손익이 붙는다. */
class FillLiveViewTest {
    private val at = Instant.parse("2026-10-08T00:27:51Z")

    private fun view(
        side: Side,
        quantity: Int,
        price: String,
        avg: String?,
    ) = FillLiveView.of(OrderFill(7, Market.KR, "233740", side, "S1", quantity, BigDecimal(price), at), avg?.let(::BigDecimal), "KODEX")

    @Test
    fun `매도 체결 줄은 체결 직전 평단 기준 손익을 보여준다`() {
        val view = view(Side.SELL, 100, "7860", "7835")

        assertEquals("+2,500 (+0.32%)", view.pnlText)
        assertEquals("pos", view.pnlCss)
        assertEquals("매수 평단 7,835 → 체결 7,860 · 100주", view.pnlDetailText)
        assertEquals("100", view.quantity.toString())
        assertEquals(at.toEpochMilli(), view.placedAtEpochMs)
    }

    @Test
    fun `손실이면 손익이 음수로 나온다`() {
        val view = view(Side.SELL, 10, "7800", "7835")

        assertEquals("-350 (-0.45%)", view.pnlText)
        assertEquals("neg", view.pnlCss)
    }

    @Test
    fun `매수 줄과 평단을 모르는 매도 줄에는 손익이 없다`() {
        assertNull(view(Side.BUY, 100, "7835", "7000").pnlText)
        assertNull(view(Side.SELL, 100, "7860", null).pnlText)
    }

    @Test
    fun `방금 반영된 이벤트로 만든 줄과 DB 에서 읽은 같은 줄은 키가 같다`() {
        val event =
            com.quantlog.position.FillAppliedEvent(Market.KR, "233740", Side.SELL, "S1", 100, BigDecimal("7860"), BigDecimal("7835"), at, 7)

        assertEquals(view(Side.SELL, 100, "7860", "7835").key, FillLiveView.of(event, "KODEX").key)
    }
}

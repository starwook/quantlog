package com.quantlog.order

import com.quantlog.broker.Market
import com.quantlog.broker.Side
import com.quantlog.position.FillSource
import com.quantlog.position.TradeFill
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 체결 내역 화면 한 줄([FillLiveView]) — 체결통보 1건이 한 줄이고, 매도 줄에만 그 체결의 손익이 붙는다. */
class FillLiveViewTest {
    private val at = Instant.parse("2026-10-08T00:27:51Z")

    private fun fill(
        side: Side,
        quantity: Int,
        price: String,
        avg: String?,
        source: FillSource = FillSource.NOTICE,
    ) = TradeFill(Market.KR, "233740", side, "S1", quantity, BigDecimal(price), avg?.let(::BigDecimal), at, source)

    @Test
    fun `매도 체결 줄은 체결 직전 평단 기준 손익을 보여준다`() {
        val view = FillLiveView.of(fill(Side.SELL, 100, "7860", "7835"), "KODEX 코스닥150레버리지")

        assertEquals("+2,500 (+0.32%)", view.pnlText)
        assertEquals("pos", view.pnlCss)
        assertEquals("매수 평단 7,835 → 체결 7,860 · 100주", view.pnlDetailText)
        assertEquals("100", view.quantity.toString())
        assertFalse(view.backfilled)
    }

    @Test
    fun `손실이면 손익이 음수로 나온다`() {
        val view = FillLiveView.of(fill(Side.SELL, 10, "7800", "7835"), "KODEX")

        assertEquals("-350 (-0.45%)", view.pnlText)
        assertEquals("neg", view.pnlCss)
    }

    @Test
    fun `매수 줄과 평단을 모르는 매도 줄에는 손익이 없다`() {
        assertNull(FillLiveView.of(fill(Side.BUY, 100, "7835", null), "KODEX").pnlText)
        assertNull(FillLiveView.of(fill(Side.SELL, 100, "7860", null), "KODEX").pnlText)
    }

    @Test
    fun `통보를 놓쳐 보충한 줄은 보충 표시를 단다`() {
        assertTrue(FillLiveView.of(fill(Side.BUY, 41, "69590", null, FillSource.REST_BACKFILL), "KODEX 코스피").backfilled)
    }
}

package com.quantlog.position

import com.quantlog.watchlist.symbolStrategyServiceOf
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.ui.ExtendedModelMap
import java.math.BigDecimal
import kotlin.test.assertEquals

class TradeControllerTest {
    private val portfolioService = Mockito.mock(PortfolioService::class.java)
    private val cashService = Mockito.mock(CashService::class.java)
    private val controller = TradeController(portfolioService, cashService, symbolStrategyServiceOf())

    private fun summary(
        currency: String,
        win: Int,
        loss: Int,
    ) = PortfolioSummary(
        currency = currency,
        holdings = emptyList(),
        holdingsValue = BigDecimal.ZERO,
        costBasis = BigDecimal.ZERO,
        unrealizedPnl = BigDecimal.ZERO,
        unrealizedPnlPercent = BigDecimal.ZERO,
        realizedPnlToday = BigDecimal.ZERO,
        realizedPnlTodayPercent = BigDecimal.ZERO,
        realizedPnlTotal = BigDecimal.ZERO,
        realizedPnlTotalPercent = BigDecimal.ZERO,
        sellWinToday = win,
        sellLossToday = loss,
    )

    private fun viewOf(
        win: Int,
        loss: Int,
    ): SummaryView {
        val summary = summary("KRW", win, loss)
        Mockito.`when`(portfolioService.snapshot())
            .thenReturn(PortfolioSnapshot(emptyList(), emptyMap(), mapOf("KRW" to summary)))
        val model = ExtendedModelMap()
        controller.trades(model)
        @Suppress("UNCHECKED_CAST")
        return (model["summaries"] as List<SummaryView>).single()
    }

    @Test
    fun `성공이 절반을 넘으면 소수점 첫째 자리까지 퍼센트를 찍고 이익색이다`() {
        val view = viewOf(win = 2, loss = 1)

        assertEquals("66.7%", view.winRateText)
        assertEquals("(성공 2건 · 실패 1건)", view.winRateDetailText)
        assertEquals("pos", view.winRateCss)
    }

    @Test
    fun `성공이 절반 미만이면 손실색이다`() {
        val view = viewOf(win = 1, loss = 2)

        assertEquals("33.3%", view.winRateText)
        assertEquals("neg", view.winRateCss)
    }

    @Test
    fun `딱 절반이면 중립색이다`() {
        val view = viewOf(win = 1, loss = 1)

        assertEquals("50.0%", view.winRateText)
        assertEquals("zero", view.winRateCss)
    }

    @Test
    fun `매도가 0건이면 퍼센트 대신 대시를 찍고 흐리게 한다`() {
        val view = viewOf(win = 0, loss = 0)

        assertEquals("—", view.winRateText)
        assertEquals("(성공 0건 · 실패 0건)", view.winRateDetailText)
        assertEquals("muted", view.winRateCss)
    }

    @Test
    fun `반올림은 HALF_UP이다`() {
        // 1/8 = 12.5% (정확히 떨어짐), 1/16 = 6.25% → 6.3%
        assertEquals("6.3%", viewOf(win = 1, loss = 15).winRateText)
    }

    @Test
    fun `주문 가능 금액을 통화와 함께 찍고 조회에 실패하면 그렇게 적는다`() {
        assertEquals("조회 실패", viewOf(win = 0, loss = 0).orderableCashText)

        Mockito.`when`(cashService.orderableCash("KRW")).thenReturn(OrderableCash(BigDecimal("9000000"), stale = false))
        assertEquals("9,000,000 KRW", viewOf(win = 0, loss = 0).orderableCashText)

        Mockito.`when`(cashService.orderableCash("KRW")).thenReturn(OrderableCash(BigDecimal("9000000"), stale = true))
        assertEquals("9,000,000 KRW (갱신 실패, 마지막 값)", viewOf(win = 0, loss = 0).orderableCashText)
    }
}

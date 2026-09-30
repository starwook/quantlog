package com.quantlog.trading

import com.quantlog.broker.Market
import com.quantlog.broker.OrderRequest
import com.quantlog.broker.Side
import com.quantlog.position.PortfolioService
import com.quantlog.position.PortfolioSnapshot
import com.quantlog.position.PortfolioSummary
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RiskGuardTest {
    private fun order(
        market: Market,
        qty: Int,
        price: String,
    ) = OrderRequest(market, "X", Side.BUY, qty, BigDecimal(price))

    private fun portfolioServiceWith(
        currency: String,
        costBasis: BigDecimal = BigDecimal.ZERO,
        realizedPnlToday: BigDecimal = BigDecimal.ZERO,
    ): PortfolioService {
        val summary =
            PortfolioSummary(
                currency = currency,
                holdings = emptyList(),
                holdingsValue = costBasis,
                costBasis = costBasis,
                unrealizedPnl = BigDecimal.ZERO,
                unrealizedPnlPercent = BigDecimal.ZERO,
                realizedPnlToday = realizedPnlToday,
                realizedPnlTodayPercent = BigDecimal.ZERO,
                realizedPnlTotal = BigDecimal.ZERO,
                realizedPnlTotalPercent = BigDecimal.ZERO,
            )
        val service = Mockito.mock(PortfolioService::class.java)
        Mockito.`when`(service.snapshot()).thenReturn(PortfolioSnapshot(emptyList(), emptyMap(), mapOf(currency to summary)))
        return service
    }

    @Test
    fun `1회 주문 상한은 없다 - 큰 주문도 check 를 통과`() {
        val guard = RiskGuard(RiskProperties(), Mockito.mock(PortfolioService::class.java))
        guard.check(order(Market.NASDAQ, 100, "150.00"))
        guard.check(order(Market.KR, 30, "272000"))
    }

    @Test
    fun `삼성전자 1-2-4-8-16주 마틴게일 매수가 국내 배분 한도 안에서 모두 통과`() {
        // 누적 31주 × 27.2만 ≈ 843만원 < 1000만원. 각 매수 직전 보유 원가를 넣어 checkBuy 를 단계별로 확인한다.
        var invested = BigDecimal.ZERO
        listOf(1, 2, 4, 8, 16).forEach { qty ->
            val guard = RiskGuard(RiskProperties(), portfolioServiceWith("KRW", costBasis = invested))
            val order = order(Market.KR, qty, "272000")
            guard.checkBuy(order)
            invested = invested.add(order.notional)
        }
        assertEquals(0, BigDecimal("8432000").compareTo(invested))
    }

    @Test
    fun `배분 한도는 국내 1000만원이 기본이라 32주째는 막힌다`() {
        val guard = RiskGuard(RiskProperties(), portfolioServiceWith("KRW", costBasis = BigDecimal("8432000")))
        assertFailsWith<RiskViolationException> { guard.checkBuy(order(Market.KR, 6, "272000")) }
    }

    @Test
    fun `기존 보유 더하기 신규 주문이 시장 배분 한도를 넘으면 매수 거부`() {
        val strict =
            RiskGuard(
                RiskProperties(marketAllocationKrw = BigDecimal("1000000")),
                portfolioServiceWith(currency = "KRW", costBasis = BigDecimal("900000")),
            )
        // 900,000(기존) + 120,000(신규) > 1,000,000 한도
        assertFailsWith<RiskViolationException> { strict.checkBuy(order(Market.KR, 2, "60000")) }
    }

    @Test
    fun `배분 한도 이내면 매수 허용`() {
        val guard =
            RiskGuard(
                RiskProperties(marketAllocationKrw = BigDecimal("1000000")),
                portfolioServiceWith(currency = "KRW", costBasis = BigDecimal("500000")),
            )
        guard.checkBuy(order(Market.KR, 1, "60000"))
    }

    @Test
    fun `오늘 실현손실이 한도를 넘으면 매수 거부`() {
        val guard =
            RiskGuard(
                RiskProperties(marketAllocationKrw = BigDecimal("1000000"), dailyLossLimitPercent = BigDecimal("3")),
                // -3% of 1,000,000 = -30,000
                portfolioServiceWith(currency = "KRW", realizedPnlToday = BigDecimal("-30000")),
            )
        assertFailsWith<RiskViolationException> { guard.checkBuy(order(Market.KR, 1, "10000")) }
    }

    @Test
    fun `오늘 실현손실이 한도 이내면 매수 허용`() {
        val guard =
            RiskGuard(
                RiskProperties(marketAllocationKrw = BigDecimal("1000000"), dailyLossLimitPercent = BigDecimal("3")),
                portfolioServiceWith(currency = "KRW", realizedPnlToday = BigDecimal("-10000")),
            )
        guard.checkBuy(order(Market.KR, 1, "10000"))
    }
}

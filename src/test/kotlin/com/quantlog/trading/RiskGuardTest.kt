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

    private val emptyPortfolio = Mockito.mock(PortfolioService::class.java)
    private val guard =
        RiskGuard(
            RiskProperties(maxOrderUsd = BigDecimal("1000"), maxOrderKrw = BigDecimal("1000000"), maxOrderQuantity = 10),
            emptyPortfolio,
        )

    @Test
    fun `한도 이내 주문은 통과`() {
        guard.check(order(Market.NASDAQ, 2, "150.00"))
        guard.check(order(Market.KR, 5, "70000"))
    }

    @Test
    fun `달러 금액 상한 초과는 거부`() {
        assertFailsWith<RiskViolationException> { guard.check(order(Market.NASDAQ, 10, "150.00")) }
    }

    @Test
    fun `원화 금액 상한 초과는 거부`() {
        assertFailsWith<RiskViolationException> { guard.check(order(Market.KR, 10, "150000")) }
    }

    @Test
    fun `수량 상한 초과는 거부`() {
        assertFailsWith<RiskViolationException> { guard.check(order(Market.NASDAQ, 11, "1.00")) }
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

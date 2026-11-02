package com.quantlog.trading

import com.quantlog.broker.Market
import com.quantlog.broker.OrderRequest
import com.quantlog.broker.Side
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertFailsWith

class RiskGuardTest {
    private fun order(
        qty: Int,
        price: String,
    ) = OrderRequest(Market.KR, "X", Side.BUY, qty, BigDecimal(price))

    @Test
    fun `1회 주문 상한은 없다 - 큰 주문도 check 를 통과`() {
        RiskGuard { null }.check(order(30, "272000"))
    }

    @Test
    fun `주문 금액이 주문가능금액보다 크면 매수 거부`() {
        val guard = RiskGuard { BigDecimal("100000") }
        // 60,000 × 2 = 120,000 > 100,000
        assertFailsWith<RiskViolationException> { guard.checkBuy(order(2, "60000")) }
    }

    @Test
    fun `주문 금액이 주문가능금액과 같거나 작으면 매수 허용 - 금액 상한은 따로 없다`() {
        RiskGuard { BigDecimal("120000") }.checkBuy(order(2, "60000"))
        // 예전 국내 1000만원 한도를 넘는 주문도 돈이 있으면 나간다
        RiskGuard { BigDecimal("50000000") }.checkBuy(order(100, "272000"))
    }

    @Test
    fun `주문가능금액을 아직 모르면 통과시킨다 - 증권사 거부에 맡긴다`() {
        RiskGuard { null }.checkBuy(order(100, "272000"))
    }

    // 평단 5,400 · 손절 1% → 손절선 5,346
    private val lines = StopLossLines { _, symbol -> if (symbol == "X") BigDecimal("5346") else null }

    @Test
    fun `손절선 이하 지정가 매수는 막는다 - 사자마자 손절로 팔게 된다`() {
        val guard = RiskGuard(TradingHalt(), lines) { null }
        assertFailsWith<BelowStopLossException> { guard.checkBuy(order(10, "5235")) }
        assertFailsWith<BelowStopLossException> { guard.checkBuy(order(10, "5346")) }
    }

    @Test
    fun `손절선 위 매수와 매도는 막지 않는다`() {
        val guard = RiskGuard(TradingHalt(), lines) { null }
        guard.checkBuy(order(10, "5350"))
        guard.check(OrderRequest(Market.KR, "X", Side.SELL, 10, BigDecimal("5000")))
    }

    @Test
    fun `보유가 없거나 손절이 보류면 손절선 검사는 없다`() {
        RiskGuard(TradingHalt(), lines) { null }.checkBuy(OrderRequest(Market.KR, "Y", Side.BUY, 10, BigDecimal("1")))
    }
}

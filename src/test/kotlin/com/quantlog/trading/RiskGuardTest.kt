package com.quantlog.trading

import com.quantlog.broker.Market
import com.quantlog.broker.OrderRequest
import com.quantlog.broker.Side
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertFailsWith

class RiskGuardTest {
    private val guard =
        RiskGuard(RiskProperties(maxOrderUsd = BigDecimal("1000"), maxOrderKrw = BigDecimal("1000000"), maxOrderQuantity = 10))

    private fun order(
        market: Market,
        qty: Int,
        price: String,
    ) = OrderRequest(market, "X", Side.BUY, qty, BigDecimal(price))

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
}

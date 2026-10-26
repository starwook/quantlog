package com.quantlog.order

import com.quantlog.broker.LatestPrices
import com.quantlog.broker.Market
import com.quantlog.position.AccountHolding
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals

/**
 * 주문 화면 보유 종목의 현재가([LiveValuation]) — 실시간 틱이 있으면 틱, 없으면 잔고 현재가.
 * 2026-10-26: 화면이 열려 있을 때만 틱을 따로 기록하던 캐시 탓에 며칠 전 가격(6,230)이 실제 5천원대 시세를 덮었다.
 */
class LiveValuationTest {
    private val latestPrices = LatestPrices()
    private val valuation = LiveValuation(latestPrices)
    private val holding = AccountHolding(Market.KR, "233740", 10, BigDecimal("4937"), BigDecimal("5100"))

    @Test
    fun `틱이 없으면 잔고 현재가를 쓴다`() {
        assertEquals(BigDecimal("5100"), valuation.priceOf(holding))
    }

    @Test
    fun `틱이 있으면 마지막 틱 가격을 쓴다`() {
        latestPrices.record("233740", BigDecimal("6230"), Instant.parse("2026-10-20T01:00:00Z"))
        latestPrices.record("233740", BigDecimal("5080"), Instant.parse("2026-10-26T06:30:00Z"))

        assertEquals(BigDecimal("5080"), valuation.priceOf(holding))
    }

    @Test
    fun `평가손익과 평가금액도 같은 현재가로 계산한다`() {
        latestPrices.record("233740", BigDecimal("5000"), Instant.parse("2026-10-26T06:30:00Z"))

        val (amount, _) = valuation.unrealizedOf(listOf(holding))

        assertEquals(0, BigDecimal("50000").compareTo(valuation.totalValueOf(listOf(holding))))
        assertEquals(0, BigDecimal("630").compareTo(amount))
    }
}

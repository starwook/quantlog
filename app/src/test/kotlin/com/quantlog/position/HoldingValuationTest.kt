package com.quantlog.position

import com.quantlog.broker.LatestPrices
import com.quantlog.broker.Market
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals

/**
 * 보유 종목의 현재가([HoldingValuation]) — 실시간 틱과 잔고 현재가 중 더 최근에 받은 값(2026-11-04 사용자 결정).
 * 2026-10-26: 화면이 열려 있을 때만 틱을 따로 기록하던 캐시 탓에 며칠 전 가격(6,230)이 실제 5천원대 시세를 덮었다.
 * 2026-11-04: 스트림이 끊겨 멈춘 틱이 그 뒤 잔고 동기화가 받은 새 현재가를 계속 덮었다(전체 점검 영역 4).
 */
class HoldingValuationTest {
    private val latestPrices = LatestPrices()
    private val valuation = HoldingValuation(latestPrices)

    /** 잔고 현재가 5,100 을 방금(updatedAt = 지금) 받은 보유 행. */
    private val holding = AccountHolding(Market.KR, "233740", 10, BigDecimal("4937"), BigDecimal("5100"))

    @Test
    fun `틱이 없으면 잔고 현재가를 쓴다`() {
        assertEquals(BigDecimal("5100"), valuation.priceOf(holding))
    }

    @Test
    fun `잔고 현재가보다 나중에 받은 틱이 있으면 마지막 틱 가격을 쓴다`() {
        latestPrices.record("233740", BigDecimal("6230"), holding.updatedAt.plusSeconds(1))
        latestPrices.record("233740", BigDecimal("5080"), holding.updatedAt.plusSeconds(2))

        assertEquals(BigDecimal("5080"), valuation.priceOf(holding))
    }

    @Test
    fun `멈춘 틱보다 나중에 받은 잔고 현재가가 있으면 잔고 현재가를 쓴다`() {
        // 10분 전에 멈춘 틱 5,300. 그 뒤 잔고 동기화가 5,100 을 받았다.
        latestPrices.record("233740", BigDecimal("5300"), holding.updatedAt.minus(Duration.ofMinutes(10)))

        assertEquals(BigDecimal("5100"), valuation.priceOf(holding))
    }

    @Test
    fun `평가손익과 평가금액도 같은 현재가로 계산한다`() {
        latestPrices.record("233740", BigDecimal("5000"), Instant.now().plusSeconds(1))

        val (amount, _) = valuation.unrealizedOf(listOf(holding))

        assertEquals(0, BigDecimal("50000").compareTo(valuation.totalValueOf(listOf(holding))))
        assertEquals(0, BigDecimal("630").compareTo(amount))
    }
}

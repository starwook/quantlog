package com.quantlog.trading

import com.quantlog.broker.OrderRequest
import com.quantlog.position.PortfolioService
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.math.BigDecimal

@ConfigurationProperties(prefix = "quantlog.risk")
data class RiskProperties(
    /** 시장별 자본 배분 한도 (playbook/principles.md: "시장별로 쓸 돈을 나눈다. 국내 1000만원 / 미국 500만원 상당"). */
    val marketAllocationKrw: BigDecimal = BigDecimal("10000000"),
    val marketAllocationUsd: BigDecimal = BigDecimal("2700"),
    /** 하루 손실 한도(킬스위치) — 배분 자본 대비 %. */
    val dailyLossLimitPercent: BigDecimal = BigDecimal("3"),
)

class RiskViolationException(message: String) : RuntimeException(message)

/**
 * 주문 직전 검문소. 모든 주문 경로는 이 검사를 통과해야 한다 (AI/규칙 어느 쪽이 낸 주문이든 우회 불가).
 * [checkBuy] 는 매수 전용 — 시장별 자본 배분·하루 손실 킬스위치까지 함께 본다. 매도는 [check] 만 거친다
 * (손실 중이어도 빠져나가는 길은 막지 않는다).
 */
@Component
class RiskGuard(
    private val properties: RiskProperties,
    private val portfolioService: PortfolioService,
) {
    /**
     * 모든 주문(매수·매도)이 거치는 자리. 1회 주문 상한(금액·수량)은 2026-09-30 사용자 결정으로 폐지해서 지금은 걸 조건이 없다 —
     * 한도는 매수 쪽 시장별 자본 배분·하루 손실 한도가 맡는다. 우회 경로를 만들지 않으려고 호출 자리는 그대로 둔다.
     */
    @Suppress("UNUSED_PARAMETER")
    fun check(order: OrderRequest) = Unit

    fun checkBuy(order: OrderRequest) {
        check(order)
        val allocation = if (order.market.isOverseas) properties.marketAllocationUsd else properties.marketAllocationKrw
        val summary = portfolioService.snapshot().summaryByCurrency[order.market.currency]
        val costBasis = summary?.costBasis ?: BigDecimal.ZERO
        val investedAfter = costBasis.add(order.notional)
        if (investedAfter > allocation) {
            throw RiskViolationException(
                "[자본 배분 초과] ${order.market} 기존 보유 $costBasis + 신규 ${order.notional} > 배분 한도 $allocation",
            )
        }
        val dailyLossLimit = allocation.multiply(properties.dailyLossLimitPercent).movePointLeft(2)
        val realizedLossToday = (summary?.realizedPnlToday ?: BigDecimal.ZERO).negate()
        if (realizedLossToday >= dailyLossLimit) {
            throw RiskViolationException(
                "[하루 손실 한도] ${order.market} 오늘 실현손실 $realizedLossToday >= 한도 $dailyLossLimit — 오늘은 신규 매수 중단",
            )
        }
    }
}

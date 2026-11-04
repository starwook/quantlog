package com.quantlog.position

import com.quantlog.broker.LatestPrices
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.MathContext

/**
 * 보유 종목의 현재가·평가손익·평가금액. 앱 전체(포트폴리오 요약·주문 화면·리뷰·진입 판단)가 이 한 곳의 현재가를 쓴다.
 * 현재가는 실시간 틱([LatestPrices] — 화면과 상관없이 모든 틱을 기록한다)과 잔고 행의 현재가(잔고 동기화가 받은 한투 `prpr`, 체결 반영 때는 체결가) 중
 * **더 최근에 받은 값**을 쓴다(2026-11-04). 틱이 없으면(앱 재시작 직후 첫 틱 전) 잔고 값. 스트림이 끊겨 틱이 멈춘 뒤 가격이 움직이면 잔고 값이 이긴다 —
 * 예전엔 몇 시간 전 틱도 그대로 써서 화면 평가손익이 판정에 쓰는 값과 달라졌다. 새 시간 기준(N초)은 두지 않는다.
 */
@Component
class HoldingValuation(
    private val latestPrices: LatestPrices,
) {
    fun priceOf(holding: AccountHolding): BigDecimal {
        val live = latestPrices.get(holding.symbol) ?: return holding.currentPrice
        return if (live.at.isBefore(holding.updatedAt)) holding.currentPrice else live.price
    }

    /** 보유 종목 전체의 평가손익(금액, 퍼센트). 현재가는 [priceOf] 규칙. */
    fun unrealizedOf(held: Collection<AccountHolding>): Pair<BigDecimal, BigDecimal> {
        val cost = held.sumOf { it.avgCost.multiply(BigDecimal(it.quantity)) }
        val amount = totalValueOf(held).subtract(cost)
        val percent = if (cost.signum() > 0) amount.multiply(BigDecimal(100)).divide(cost, MathContext.DECIMAL64) else BigDecimal.ZERO
        return amount to percent
    }

    /** 보유 종목 전체의 총 평가금액. 현재가는 [priceOf] 규칙. */
    fun totalValueOf(held: Collection<AccountHolding>): BigDecimal = held.sumOf { priceOf(it).multiply(BigDecimal(it.quantity)) }
}

package com.quantlog.order

import com.quantlog.broker.LatestPrices
import com.quantlog.position.AccountHolding
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.MathContext

/**
 * 보유 종목의 현재가·평가손익·평가금액. 현재가는 실시간 틱([LatestPrices] — 화면이 열려 있든 아니든 모든 틱을 기록한다)을,
 * 틱이 없으면(앱 재시작 직후 첫 틱 전) 잔고 동기화가 받은 한투 현재가(`prpr`)를 쓴다. 장 마감 후엔 마지막 틱이 종가다.
 */
@Component
class LiveValuation(
    private val latestPrices: LatestPrices,
) {
    fun priceOf(holding: AccountHolding): BigDecimal = latestPrices.get(holding.symbol)?.price ?: holding.currentPrice

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

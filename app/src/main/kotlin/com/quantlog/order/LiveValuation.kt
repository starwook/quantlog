package com.quantlog.order

import com.quantlog.position.AccountHolding
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.MathContext
import java.util.concurrent.ConcurrentHashMap

/** 실시간 체결가(틱)를 종목별로 기억해, 보유 종목의 평가손익·평가금액을 잔고 사본 값 대신 최신 가격으로 계산한다. */
@Component
class LiveValuation {
    private val prices = ConcurrentHashMap<String, BigDecimal>()

    fun record(
        symbol: String,
        price: BigDecimal,
    ) {
        prices[symbol] = price
    }

    /** 실시간 체결가. 틱을 받은 적 없으면 null. */
    fun priceOf(symbol: String): BigDecimal? = prices[symbol]

    /** 보유 종목 전체의 평가손익(금액, 퍼센트). 현재가는 실시간 틱이 있으면 그걸, 없으면 잔고 사본 값을 쓴다. */
    fun unrealizedOf(held: Collection<AccountHolding>): Pair<BigDecimal, BigDecimal> {
        val cost = held.sumOf { it.avgCost.multiply(BigDecimal(it.quantity)) }
        val amount = totalValueOf(held).subtract(cost)
        val percent = if (cost.signum() > 0) amount.multiply(BigDecimal(100)).divide(cost, MathContext.DECIMAL64) else BigDecimal.ZERO
        return amount to percent
    }

    /** 보유 종목 전체의 총 평가금액. 현재가는 [unrealizedOf] 와 같은 규칙. */
    fun totalValueOf(held: Collection<AccountHolding>): BigDecimal =
        held.sumOf { (prices[it.symbol] ?: it.currentPrice).multiply(BigDecimal(it.quantity)) }
}

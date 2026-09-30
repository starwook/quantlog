package com.quantlog.strategy

import com.quantlog.broker.Quote
import com.quantlog.broker.Side
import com.quantlog.position.Trade
import org.springframework.boot.context.properties.ConfigurationProperties
import java.math.BigDecimal
import java.math.MathContext

/**
 * 2026-09-30 사용자 지정: 첫 1주 매수 후 평단 대비 0.5% 떨어질 때마다 보유 수량이 2배가 되도록 추가 매수(최대 5단계),
 * 최대 단계까지 산 뒤 평단 대비 3% 더 떨어지면 손절. 익절 뒤엔 매도가 -0.5%, 손절 뒤엔 매도가 -1%에서 재진입.
 * (처음 -1%로 시작했다가 "오늘은 수익률보다 최대한 많이 거래"가 목표라 0.5%로 줄임.)
 *
 * 종목별 실제 값은 DB(watchlist.SymbolStrategy)가 갖고, 여기(application.yml)는 새 종목 행을 만들 때 쓰는 기본값이다.
 */
@ConfigurationProperties(prefix = "quantlog.strategy.martingale")
data class MartingaleProperties(
    val dropPercent: BigDecimal = BigDecimal("0.5"),
    val reentryDropPercent: BigDecimal = BigDecimal("0.5"),
    val stopReentryDropPercent: BigDecimal = BigDecimal("1"),
    val multiplier: Int = 2,
    val maxStages: Int = 5,
    val finalStageStopLossPercent: BigDecimal = BigDecimal("3"),
)

data class CycleBuy(val quantity: Int, val price: BigDecimal)

/** [takeProfit] 이 false 면 손절로 끝난 사이클이다. */
data class CycleSell(val price: BigDecimal, val takeProfit: Boolean)

/**
 * 매매 기록에서 계산한 현재 사이클: 마지막 SELL 이후의 BUY 들. 별도 상태는 저장하지 않는다.
 * [lastSell] 은 그 직전에 끝난 사이클의 매도다.
 *
 * 익절/손절 구분은 "매도가 > 그 사이클 평단"으로 한다. 매도 사유 컬럼을 새로 두면 스키마·기록 경로가 늘어나는데,
 * 이 전략에선 손절 매도가가 항상 평단보다 한참 아래(평단 -3%)이고 익절은 평단 +1% 근처라 가격만으로 갈린다.
 */
data class MartingaleCycle(
    val buys: List<CycleBuy>,
    val lastSell: CycleSell?,
) {
    val stage: Int get() = buys.size
    val holding: Boolean get() = buys.isNotEmpty()

    /** 이 사이클에서 지금 들고 있는 총 수량. */
    val quantity: Int get() = buys.sumOf { it.quantity }

    /** 이 사이클의 평단(수량 가중 평균). 보유 중이 아니면 null. */
    val averagePrice: BigDecimal?
        get() {
            if (buys.isEmpty()) return null
            return buys.sumOf { it.price.multiply(BigDecimal(it.quantity)) }.divide(BigDecimal(quantity), MathContext.DECIMAL64)
        }

    companion object {
        /** [trades] 는 체결 시각 오름차순. 체결가가 없으면 지정가로 대신한다. */
        fun from(trades: List<Trade>): MartingaleCycle {
            val buys = mutableListOf<CycleBuy>()
            var lastSell: CycleSell? = null
            trades.forEach { trade ->
                val price = trade.filledPrice ?: trade.orderPrice
                when (trade.side) {
                    Side.BUY -> buys += CycleBuy(trade.quantity, price)
                    Side.SELL ->
                        if (buys.isNotEmpty()) {
                            val quantity = buys.sumOf { it.quantity }
                            val cost = buys.sumOf { it.price.multiply(BigDecimal(it.quantity)) }
                            val average = cost.divide(BigDecimal(quantity), MathContext.DECIMAL64)
                            lastSell = CycleSell(price, takeProfit = price > average)
                            buys.clear()
                        }
                }
            }
            return MartingaleCycle(buys.toList(), lastSell)
        }
    }
}

/**
 * 마틴게일 판정. 추가 매수 기준은 **평단**이다(2026-09-30 사용자 결정: 증권사 앱에서 직접 산 물량도 KIS 잔고 동기화로
 * 매매 기록에 들어오므로 "직전 매수가"보다 평단이 실제 보유 상태를 그대로 반영한다). 추가 매수 수량도 직전 매수가 아니라 현재 보유 수량 기준이다.
 * 모든 가격은 호가 단위로 맞춘다(청산 목표가와 같은 방식).
 * 물타기(평단 낮추기)를 금지하던 원칙은 이 전략과 정면으로 충돌해서 폐기했다(playbook/principles.md).
 */
class MartingaleRule(
    private val properties: MartingaleProperties,
) {
    private fun below(
        price: BigDecimal,
        percent: BigDecimal,
        quote: Quote,
    ): BigDecimal = quote.roundToTick(price.multiply(BigDecimal.ONE.subtract(percent.movePointLeft(2))))

    /** 추가 매수 트리거 가격: 평단 -0.5%. */
    fun addOnTriggerPrice(
        averagePrice: BigDecimal,
        quote: Quote,
    ): BigDecimal = below(averagePrice, properties.dropPercent, quote)

    /** 추가로 사야 할 수량. 보유 중이 아니거나, 최대 단계까지 샀거나, 아직 트리거까지 안 내려왔으면 null. */
    fun nextQuantity(
        cycle: MartingaleCycle,
        quote: Quote,
    ): Int? {
        val average = cycle.averagePrice ?: return null
        if (cycle.stage >= properties.maxStages) return null
        // 배수는 보유 수량의 배수다: 2배면 지금 들고 있는 만큼을 더 사서 보유가 2배가 된다(1주 → 1주 더 → 2주 더 → 4주 더 ...).
        val quantity = cycle.quantity * (properties.multiplier - 1)
        return if (quote.price <= addOnTriggerPrice(average, quote)) quantity else null
    }

    /** 최대 단계 매수를 마친 뒤에만 있는 손절가: 평단 -3%(2026-09-30 사용자 결정: 마지막 매수가는 불안정해서 평단 기준). 그 전 단계엔 null(손절 없음). */
    fun stopLossPrice(
        cycle: MartingaleCycle,
        quote: Quote,
    ): BigDecimal? {
        if (cycle.stage < properties.maxStages) return null
        return below(cycle.averagePrice ?: return null, properties.finalStageStopLossPercent, quote)
    }

    fun shouldStopLoss(
        cycle: MartingaleCycle,
        quote: Quote,
    ): Boolean = stopLossPrice(cycle, quote)?.let { quote.price <= it } == true

    /** 재진입 트리거 가격: 익절 뒤엔 그 매도가 -0.5%, 손절 뒤엔 -1%. 보유 중이거나 매도 기록이 없으면 null. */
    fun reentryTriggerPrice(
        cycle: MartingaleCycle,
        quote: Quote,
    ): BigDecimal? {
        val sell = cycle.lastSell?.takeUnless { cycle.holding } ?: return null
        return below(sell.price, if (sell.takeProfit) properties.reentryDropPercent else properties.stopReentryDropPercent, quote)
    }

    fun shouldReenter(
        cycle: MartingaleCycle,
        quote: Quote,
    ): Boolean = reentryTriggerPrice(cycle, quote)?.let { quote.price <= it } == true
}

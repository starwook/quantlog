package com.quantlog.strategy

import com.quantlog.broker.Quote
import com.quantlog.broker.Side
import com.quantlog.position.Trade
import org.springframework.boot.context.properties.ConfigurationProperties
import java.math.BigDecimal
import java.math.MathContext

/**
 * 2026-09-30 사용자 지정: 첫 1주 매수 후 평단 대비 0.5% 떨어질 때마다 보유 수량이 2배가 되도록 추가 매수(최대 5단계),
 * (손절은 마틴게일 전용이 없고 종목의 손절 %(SymbolStrategy.stopLossPercent) 하나로 단계와 무관하게 평단 기준 적용 — 2026-10-07.) (매도 뒤 재진입은 2026-10-02 폐기 — 첫 진입은 5분 재매수·저점 판단 진입 옵션이 맡는다.)
 * (처음 -1%로 시작했다가 "오늘은 수익률보다 최대한 많이 거래"가 목표라 0.5%로 줄임.)
 *
 * 종목별 실제 값은 DB(watchlist.SymbolStrategy)가 갖고, 여기(application.yml)는 새 종목 행을 만들 때 쓰는 기본값이다.
 */
@ConfigurationProperties(prefix = "quantlog.strategy.martingale")
data class MartingaleProperties(
    val dropPercent: BigDecimal = BigDecimal("0.5"),
    val multiplier: Int = 2,
    val maxStages: Int = 5,
)

data class CycleBuy(val quantity: Int, val price: BigDecimal)

/**
 * 매매 기록에서 계산한 현재 사이클: 마지막 SELL 이후의 BUY 들. 별도 상태는 저장하지 않는다.
 */
data class MartingaleCycle(
    val buys: List<CycleBuy>,
    /** KIS 잔고 테이블의 실제 보유 수량·평단. 있으면 매매 기록 대신 이 값이 기준이다([withAccount]). */
    private val accountQuantity: Int? = null,
    private val accountAverage: BigDecimal? = null,
) {
    /** 단계 = 사이클의 매수 건수. 증권사 앱에서 직접 산 물량만 있고 봇 매수가 없어도 보유 중이면 최소 1단계. */
    val stage: Int get() = if (accountQuantity != null) maxOf(buys.size, 1) else buys.size
    val holding: Boolean get() = accountQuantity != null || buys.isNotEmpty()

    /** 이 사이클에서 지금 들고 있는 총 수량. */
    val quantity: Int get() = accountQuantity ?: buys.sumOf { it.quantity }

    /** 이 사이클의 평단. 잔고가 있으면 그 평단, 없으면 매수 기록의 수량 가중 평균. 보유 중이 아니면 null. */
    val averagePrice: BigDecimal?
        get() {
            if (accountAverage != null) return accountAverage
            if (buys.isEmpty()) return null
            return buys.sumOf { it.price.multiply(BigDecimal(it.quantity)) }.divide(BigDecimal(quantity), MathContext.DECIMAL64)
        }

    /**
     * 실제 보유(KIS 잔고 테이블)를 반영한다: 잔고에 없으면 보유 중이 아니고(매매 기록상 매수가 남았어도 앱에서 팔았을 수 있다),
     * 있으면 수량·평단은 잔고 값을 쓴다. 단계와 직전 매도는 매매 기록에서 온다.
     */
    fun withAccount(
        quantity: Int?,
        average: BigDecimal?,
    ): MartingaleCycle =
        if (quantity == null || quantity <= 0) {
            copy(buys = emptyList(), accountQuantity = null, accountAverage = null)
        } else {
            copy(accountQuantity = quantity, accountAverage = average)
        }

    companion object {
        /** [trades] 는 체결 시각 오름차순. 체결가가 없으면 지정가로 대신한다. */
        fun from(trades: List<Trade>): MartingaleCycle {
            val buys = mutableListOf<CycleBuy>()
            trades.forEach { trade ->
                when (trade.side) {
                    Side.BUY -> buys += CycleBuy(trade.quantity, trade.filledPrice ?: trade.orderPrice)
                    Side.SELL -> buys.clear()
                }
            }
            return MartingaleCycle(buys.toList())
        }
    }
}

/**
 * 마틴게일 판정. 추가 매수 기준은 **평단**이다(2026-09-30 사용자 결정: 증권사 앱에서 직접 산 물량도 KIS 잔고에
 * 들어 있으므로 "직전 매수가"보다 평단이 실제 보유 상태를 그대로 반영한다). 추가 매수 수량도 직전 매수가 아니라 현재 보유 수량 기준이다.
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
}

package com.quantlog.strategy

import com.quantlog.broker.Quote
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.math.BigDecimal

enum class ExitSignal { HOLD, TAKE_PROFIT, STOP_LOSS }

/**
 * 청산 기준. 초기값은 익절 +0.5%이며, 학습 루프(기획서 6.4)가 조정하는 대상이다.
 * 손절은 null 이면 보류 — 아무리 떨어져도 손절 매도를 내지 않는다(2026-09-30: 사용자가 "손절 -1%는 보류"로 정함).
 */
@ConfigurationProperties(prefix = "quantlog.strategy")
data class StrategyProperties(
    val takeProfitPercent: BigDecimal = BigDecimal("0.5"),
    val stopLossPercent: BigDecimal? = null,
)

/** 청산 목표가. 주문 가능한 가격(호가 단위의 배수)이라 그대로 지정가로 낼 수 있다. */
data class ExitTargets(
    val takeProfitPrice: BigDecimal,
    /** 손절이 보류 중이면 null. */
    val stopLossPrice: BigDecimal?,
)

/** 매수 평균가 대비 고정 비율 손절/익절(손절은 null 이면 보류). 개별 청산 판정은 AI 개입 없이 기계적으로 한다. */
class FixedPercentExitRule(
    private val takeProfitPercent: BigDecimal,
    private val stopLossPercent: BigDecimal?,
) {
    /**
     * 평단 ±비율에 가장 가까운 호가를 목표가로 잡는다 (예: 평단 272,000원, 호가 500원 → 익절 274,500 / 손절 269,500).
     * 호가 단위는 현재가 기준이라, 목표가가 다른 가격대 경계를 넘으면 드물게 어긋날 수 있다.
     */
    fun targets(
        averagePrice: BigDecimal,
        quote: Quote,
    ): ExitTargets {
        require(averagePrice > BigDecimal.ZERO) { "averagePrice must be positive: $averagePrice" }
        return ExitTargets(
            takeProfitPrice = quote.roundToTick(averagePrice.multiply(BigDecimal.ONE.add(takeProfitPercent.movePointLeft(2)))),
            stopLossPrice =
                stopLossPercent?.let { quote.roundToTick(averagePrice.multiply(BigDecimal.ONE.subtract(it.movePointLeft(2)))) },
        )
    }

    fun evaluate(
        averagePrice: BigDecimal,
        quote: Quote,
    ): ExitSignal {
        val targets = targets(averagePrice, quote)
        return when {
            quote.price >= targets.takeProfitPrice -> ExitSignal.TAKE_PROFIT
            targets.stopLossPrice != null && quote.price <= targets.stopLossPrice -> ExitSignal.STOP_LOSS
            else -> ExitSignal.HOLD
        }
    }
}

@Configuration
class StrategyConfig {
    @Bean
    fun exitRule(properties: StrategyProperties) = FixedPercentExitRule(properties.takeProfitPercent, properties.stopLossPercent)
}

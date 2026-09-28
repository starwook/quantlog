package com.quantlog.strategy

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.math.BigDecimal
import java.math.MathContext

enum class ExitSignal { HOLD, TAKE_PROFIT, STOP_LOSS }

/** 청산 기준. 초기값은 익절 +1% / 손절 -1%이며, 학습 루프(기획서 6.4)가 조정하는 대상이다. */
@ConfigurationProperties(prefix = "quantlog.strategy")
data class StrategyProperties(
    val takeProfitPercent: BigDecimal = BigDecimal("1"),
    val stopLossPercent: BigDecimal = BigDecimal("1"),
)

/** 매수 평균가 대비 고정 비율 손절/익절. 개별 청산 판정은 AI 개입 없이 기계적으로 한다. */
class FixedPercentExitRule(
    private val takeProfitPercent: BigDecimal,
    private val stopLossPercent: BigDecimal,
) {
    fun evaluate(
        averagePrice: BigDecimal,
        currentPrice: BigDecimal,
    ): ExitSignal {
        require(averagePrice > BigDecimal.ZERO) { "averagePrice must be positive: $averagePrice" }
        val changePercent =
            currentPrice.subtract(averagePrice)
                .multiply(HUNDRED)
                .divide(averagePrice, MathContext.DECIMAL64)
        return when {
            changePercent >= takeProfitPercent -> ExitSignal.TAKE_PROFIT
            changePercent <= stopLossPercent.negate() -> ExitSignal.STOP_LOSS
            else -> ExitSignal.HOLD
        }
    }

    private companion object {
        val HUNDRED = BigDecimal(100)
    }
}

@Configuration
class StrategyConfig {
    @Bean
    fun exitRule(properties: StrategyProperties) = FixedPercentExitRule(properties.takeProfitPercent, properties.stopLossPercent)
}

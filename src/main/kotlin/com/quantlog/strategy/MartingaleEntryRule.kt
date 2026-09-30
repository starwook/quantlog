package com.quantlog.strategy

import com.quantlog.broker.Quote
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.math.BigDecimal

/** 2026-09-30 사용자 지정: 첫 1주 매수 후 직전 매수가 대비 1% 떨어질 때마다 직전 매수 수량의 2배 추가 매수. */
@ConfigurationProperties(prefix = "quantlog.strategy.martingale")
data class MartingaleProperties(
    val dropPercent: BigDecimal = BigDecimal("1"),
    val multiplier: Int = 2,
)

/**
 * 이미 들고 있는 종목의 추가 매수 판정. 기준은 평단이 아니라 **직전 매수가**다 — 추가 매수로 평단이 내려가도
 * 다음 트리거는 "방금 산 가격에서 또 1%"이다. 트리거 가격은 호가 단위로 맞춘다(청산 목표가와 같은 방식).
 * 물타기(평단 낮추기)를 금지하던 원칙은 이 전략과 정면으로 충돌해서 폐기했다(playbook/principles.md).
 */
class MartingaleEntryRule(
    private val properties: MartingaleProperties,
) {
    fun triggerPrice(
        lastBuyPrice: BigDecimal,
        quote: Quote,
    ): BigDecimal = quote.roundToTick(lastBuyPrice.multiply(BigDecimal.ONE.subtract(properties.dropPercent.movePointLeft(2))))

    /** 추가로 사야 할 수량. 아직 트리거 가격까지 안 내려왔으면 null. */
    fun nextQuantity(
        lastBuyPrice: BigDecimal,
        lastBuyQuantity: Int,
        quote: Quote,
    ): Int? = if (quote.price <= triggerPrice(lastBuyPrice, quote)) lastBuyQuantity * properties.multiplier else null
}

@Configuration
class MartingaleConfig {
    @Bean
    fun martingaleEntryRule(properties: MartingaleProperties) = MartingaleEntryRule(properties)
}

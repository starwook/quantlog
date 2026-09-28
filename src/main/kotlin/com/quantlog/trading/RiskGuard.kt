package com.quantlog.trading

import com.quantlog.broker.OrderRequest
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.math.BigDecimal

@ConfigurationProperties(prefix = "quantlog.risk")
data class RiskProperties(
    val maxOrderUsd: BigDecimal = BigDecimal("1000"),
    val maxOrderKrw: BigDecimal = BigDecimal("1000000"),
    val maxOrderQuantity: Int = 10,
)

class RiskViolationException(message: String) : RuntimeException(message)

/** 주문 직전 검문소. 모든 주문 경로는 이 검사를 통과해야 한다 (AI/규칙 어느 쪽이 낸 주문이든 우회 불가). */
@Component
class RiskGuard(private val properties: RiskProperties) {
    fun check(order: OrderRequest) {
        if (order.quantity > properties.maxOrderQuantity) {
            throw RiskViolationException("주문 수량 ${order.quantity} > 상한 ${properties.maxOrderQuantity}")
        }
        val limit = if (order.market.isOverseas) properties.maxOrderUsd else properties.maxOrderKrw
        if (order.notional > limit) {
            throw RiskViolationException("주문 금액 ${order.notional} ${order.market.currency} > 상한 $limit")
        }
    }
}

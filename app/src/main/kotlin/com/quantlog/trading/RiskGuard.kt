package com.quantlog.trading

import com.quantlog.broker.OrderRequest
import com.quantlog.position.OrderableCash
import org.springframework.stereotype.Component

class RiskViolationException(message: String) : RuntimeException(message)

/**
 * 주문 직전 검문소. 모든 주문 경로는 이 검사를 통과해야 한다 (AI/규칙 어느 쪽이 낸 주문이든 우회 불가).
 * [checkBuy] 는 매수 전용 — 주문가능금액까지 함께 본다. 매도는 [check] 만 거친다
 * (손실 중이어도 빠져나가는 길은 막지 않는다).
 */
@Component
class RiskGuard(
    private val cash: OrderableCash,
) {
    /**
     * 모든 주문(매수·매도)이 거치는 자리. 1회 주문 상한(금액·수량)은 2026-09-30 사용자 결정으로 폐지해서 지금은 걸 조건이 없다 —
     * 한도는 매수 쪽 주문가능금액이 맡는다(하루 손실 한도는 2026-10-08 사용자 결정으로 폐지). 우회 경로를 만들지 않으려고 호출 자리는 그대로 둔다.
     */
    @Suppress("UNUSED_PARAMETER")
    fun check(order: OrderRequest) = Unit

    /**
     * 주문 금액(지정가 × 수량)이 주문가능금액보다 크면 거부한다. 시장별 자본 배분 한도(국내 1000만원)는 2026-10-13 사용자 결정으로 폐지 —
     * 계좌에 남은 돈이 한도다. 주문가능금액을 아직 모르면(기동 직후 첫 잔고 전, 모의 브로커) 통과시키고 증권사의 거부에 맡긴다.
     */
    fun checkBuy(order: OrderRequest) {
        check(order)
        val orderable = cash.orderable(order.market) ?: return
        if (order.notional > orderable) {
            throw RiskViolationException(
                "[주문가능금액 부족] ${order.market} 주문 ${order.notional.toPlainString()} > 주문가능 ${orderable.toPlainString()}",
            )
        }
    }
}

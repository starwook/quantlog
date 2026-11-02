package com.quantlog.trading

import com.quantlog.broker.OrderRequest
import com.quantlog.broker.Side
import com.quantlog.position.OrderableCash
import mu.KLogger
import org.springframework.stereotype.Component

open class RiskViolationException(message: String) : RuntimeException(message)

/** 거래 정지 중이라 막힌 주문. 정지는 몇 초 안에 풀리는 게 보통이라 스택은 남기지 않는다(틱마다 로그가 쌓인다). */
class TradingHaltedException(message: String) : RiskViolationException(message) {
    override fun fillInStackTrace(): Throwable = this
}

/** 손절선 이하라 막힌 매수. 떨어지는 동안 틱마다 다시 판정되므로 스택은 남기지 않는다. */
class BelowStopLossException(message: String) : RiskViolationException(message) {
    override fun fillInStackTrace(): Throwable = this
}

/**
 * 주문 경로의 실패를 경고로 남기되, 정해진 규칙대로 막힌 것(거래 정지·손절선 아래 매수)은 경고로 찍지 않는다 — WARN 은 웹훅 알림이 되는데
 * (notification/ErrorLogNotifier.kt) 정지는 매시 정각마다 있고, 손절선 아래 매수 거부는 떨어지는 동안 틱마다 나온다.
 */
internal fun KLogger.warnUnlessHalted(
    e: Throwable,
    message: () -> String,
) = if (e is TradingHaltedException || e is BelowStopLossException) debug { "${message()} — ${e.message}" } else warn(e) { message() }

/**
 * 주문 직전 검문소. 모든 주문 경로는 이 검사를 통과해야 한다 (AI/규칙 어느 쪽이 낸 주문이든 우회 불가).
 * [checkBuy] 는 매수 전용 — 주문가능금액까지 함께 본다. 매도는 [check] 만 거친다.
 * **매도는 어떤 경우에도 막지 않는다**(손절·익절·수동 매도 모두, 거래 정지 중에도) — 2026-11-02 사용자 결정 "손절을 포함한 모든 매도 주문이 막히지 않아야지".
 * 거래 정지([TradingHalt])는 매수만 막는다. 정지 중의 보유 수량이 낡아 실제보다 많이 팔려 하면 증권사가 거절할 뿐이다.
 */
@Component
class RiskGuard(
    private val halt: TradingHalt = TradingHalt(),
    private val stopLossLines: StopLossLines = StopLossLines { _, _ -> null },
    private val cash: OrderableCash,
) {
    /**
     * 모든 주문(매수·매도)이 거치는 자리. 거래 정지 중이면 **매수만** 막는다 — 체결통보가 빠졌을 수 있어 보유·평단을 믿을 수 없는 동안이다(2026-10-14).
     * 매도는 막지 않는다(2026-11-02). 1회 주문 상한(금액·수량)은 2026-09-30 사용자 결정으로 폐지했고, 한도는 매수 쪽 주문가능금액이 맡는다
     * (하루 손실 한도는 2026-10-08 사용자 결정으로 폐지).
     */
    fun check(order: OrderRequest) {
        if (order.side == Side.SELL) return
        halt.reason?.let { throw TradingHaltedException("[거래 정지] ${order.market} ${order.symbol} ${order.side} 주문을 막았다: $it") }
    }

    /**
     * 주문 금액(지정가 × 수량)이 주문가능금액보다 크면 거부한다. 시장별 자본 배분 한도(국내 1000만원)는 2026-10-13 사용자 결정으로 폐지 —
     * 계좌에 남은 돈이 한도다. 주문가능금액을 아직 모르면(기동 직후 첫 잔고 전, 모의 브로커) 통과시키고 증권사의 거부에 맡긴다.
     */
    fun checkBuy(order: OrderRequest) {
        check(order)
        // 손절선 이하에서 사면 사자마자 손절로 판다 — 매수(자동·수동)를 막는다(2026-11-02 사용자 결정. 10-29 233740 이 손절선 5360 아래 5235 에 샀다).
        stopLossLines.of(order.market, order.symbol)?.let { line ->
            if (order.limitPrice <= line) {
                throw BelowStopLossException(
                    "[손절선 아래 매수 금지] ${order.market} ${order.symbol} 지정가 ${order.limitPrice.toPlainString()} ≤ 손절선 ${line.toPlainString()}",
                )
            }
        }
        val orderable = cash.orderable(order.market) ?: return
        if (order.notional > orderable) {
            throw RiskViolationException(
                "[주문가능금액 부족] ${order.market} 주문 ${order.notional.toPlainString()} > 주문가능 ${orderable.toPlainString()}",
            )
        }
    }
}

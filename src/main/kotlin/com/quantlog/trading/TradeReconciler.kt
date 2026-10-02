package com.quantlog.trading

import com.quantlog.broker.BrokerClient
import com.quantlog.position.TradeFilledEvent
import com.quantlog.position.TradeRepository
import mu.KotlinLogging
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.ApplicationEventPublisher
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

private val log = KotlinLogging.logger {}

@ConfigurationProperties(prefix = "quantlog.reconcile")
data class ReconcileProperties(
    val enabled: Boolean = true,
)

/**
 * KIS 가 "정답"이고 우리 DB(Trade.filledPrice)는 그걸 따라가는 사본일 뿐이다 — 주문 직후 한 번 조회해서
 * 못 구한 체결가를 이 배치가 다시 물어봐서 채운다. 국내·해외 모두 대상이다(해외는 어제~오늘 주문만 조회되므로 그보다 오래된 건 계속 null 로 남을 수 있다).
 * (2026-09-29: 지정가를 체결가처럼 저장했던 버그를 고치면서 "그때 못 구하면 영영 못 구한다"는
 * 구멍이 남아 있어 추가함 — 사용자 지적: "DB가 아니라 KIS API를 따라가는 게 맞지 않냐".)
 */
@Component
class TradeReconciler(
    private val broker: BrokerClient,
    private val tradeRepository: TradeRepository,
    private val properties: ReconcileProperties,
    private val events: ApplicationEventPublisher,
) {
    @Scheduled(
        fixedDelayString = "\${quantlog.reconcile.interval-millis:60000}",
        // ExitScheduler(15s)와 SmokeTestRunner(부팅 시 1회)랑 안 겹치게 늦게 시작 (2026-09-29: 겹쳐서 초당 한도 걸림).
        initialDelayString = "\${quantlog.reconcile.initial-delay-millis:30000}",
    )
    fun run() {
        if (properties.enabled) reconcile()
    }

    fun reconcile() {
        val pending = tradeRepository.findAllByFilledPriceIsNull()
        if (pending.isEmpty()) return
        pending.forEach { trade ->
            runCatching { broker.filledPrice(trade.market, trade.orderNo) }
                .onSuccess { price ->
                    if (price != null) {
                        trade.applyFilledPrice(price)
                        tradeRepository.save(trade)
                        events.publishEvent(TradeFilledEvent(trade.market, trade.symbol))
                        log.info { "[체결가 확정] ${trade.market} ${trade.symbol} 주문번호=${trade.orderNo} → $price" }
                    }
                }
                .onFailure { log.warn(it) { "[체결가 재확인 실패] ${trade.market} ${trade.symbol} 주문번호=${trade.orderNo}" } }
        }
    }
}

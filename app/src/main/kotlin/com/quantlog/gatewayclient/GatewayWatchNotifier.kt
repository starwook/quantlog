package com.quantlog.gatewayclient

import com.quantlog.watchlist.WatchSymbolsChanged
import mu.KotlinLogging
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

private val log = KotlinLogging.logger {}

/**
 * 감시 종목이 추가·삭제돼 DB 에 커밋되면 게이트웨이에 알려 실시간 구독을 바로 맞추게 한다.
 * 알림이 실패해도 종목은 이미 `watch_symbol` 에 들어 있고 게이트웨이가 주기적으로 다시 읽어 맞추므로, 경고만 남기고 넘어간다.
 *
 * 동기화 불일치 보고 불필요: 외부 API 와 비교하는 일이 아니라 "다시 읽어라"는 신호이고, 어긋남은 게이트웨이의 주기 재조정이 바로잡는다.
 */
@Component
class GatewayWatchNotifier(
    private val properties: GatewayClientProperties,
    private val gateway: GatewayBrokerClient,
) {
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun onChanged(event: WatchSymbolsChanged) {
        if (!properties.enabled) return
        runCatching { gateway.refreshWatchSymbols() }
            .onFailure { log.warn { "[감시 종목] 게이트웨이 즉시 구독 요청 실패 — 게이트웨이가 10초 안에 DB 기준으로 맞춘다: ${it.message}" } }
    }
}

package com.quantlog.futures

import com.quantlog.gatewayclient.WatchSymbolRow
import com.quantlog.gatewayclient.WatchSymbolRowRepository
import com.quantlog.watchlist.WatchSymbolsChanged
import mu.KotlinLogging
import org.springframework.context.ApplicationEventPublisher
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

private val log = KotlinLogging.logger {}

/**
 * 게이트웨이가 야간선물 실시간 체결을 구독할 종목(가장 가까운 월물)을 `watch_symbol` 에 market=[MARKET] 로 써 준다
 * (docs/contracts/README.md). 월물이 바뀌면 이전 코드는 지운다. 바뀌었을 때만 게이트웨이에 알린다.
 *
 * 동기화 불일치 보고 불필요: 외부 API 와 비교하는 일이 아니라 구독 대상을 알려 주는 쓰기다.
 */
@Component
class NightFuturesWatch(
    private val watchSymbols: WatchSymbolRowRepository,
    private val client: FuturesOptionsClient,
    private val events: ApplicationEventPublisher,
) {
    /** 앱이 켜진 직후와 그 뒤 한 시간마다 가장 가까운 월물 코드를 확인한다. 실패하면 다음 주기에 다시 한다. */
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    fun refresh() {
        runCatching { client.nearestFuturesCode()?.let(::ensure) }
            .onFailure { log.warn { "[야간선물] 구독 종목 확인 실패: ${it.message}" } }
    }

    @Transactional
    fun ensure(code: String) {
        val current = watchSymbols.findAllByMarket(MARKET)
        if (current.size == 1 && current.single().symbol == code) return
        watchSymbols.deleteAll(current)
        watchSymbols.save(WatchSymbolRow(MARKET, code, false))
        events.publishEvent(WatchSymbolsChanged)
        log.info { "[야간선물] 구독 종목을 $code 로 맞췄다" }
    }

    companion object {
        const val MARKET = "NIGHT_FUTURES"
    }
}

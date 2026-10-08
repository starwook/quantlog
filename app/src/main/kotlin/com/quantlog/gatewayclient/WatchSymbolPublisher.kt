package com.quantlog.gatewayclient

import com.quantlog.watchlist.SymbolStrategyService
import mu.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

private val log = KotlinLogging.logger {}

/**
 * 감시 종목(`symbol_strategy`)을 게이트웨이가 읽는 계약 테이블 `watch_symbol`(market, symbol, etf)로 옮겨 적는다. 게이트웨이는 앱의 테이블을
 * 읽지 않고 이 좁은 테이블만 읽어 실시간 구독·분봉 수집 종목을 정한다. 종목이 추가·삭제되면 몇 초 안에 따라간다.
 *
 * 동기화 불일치 보고 불필요: 외부 API 와 비교하는 게 아니라 우리 테이블 한 개를 다른 한 개로 옮겨 적는 일이다.
 */
@Component
class WatchSymbolPublisher(
    private val strategies: SymbolStrategyService,
    private val watch: WatchSymbolRowRepository,
) {
    @Scheduled(fixedDelay = 10_000, initialDelay = 1_000)
    fun run() {
        runCatching { publish() }.onFailure { log.warn(it) { "[감시 종목 공개] 실패, 다음 회차에 재시도: ${it.message}" } }
    }

    @Transactional
    fun publish() {
        val wanted = strategies.all().associateBy { it.market.name to it.symbol }
        val current = watch.findAll().associateBy { it.market to it.symbol }
        current.filterKeys { it !in wanted }.values.forEach { watch.delete(it) }
        wanted.forEach { (key, strategy) ->
            val row = current[key]
            if (row == null) {
                watch.save(WatchSymbolRow(key.first, key.second, strategy.etf))
            } else if (row.etf != strategy.etf) {
                row.etf = strategy.etf
            }
        }
    }
}

package com.quantlog.gateway.record

import com.quantlog.gateway.broker.Market
import com.quantlog.gateway.broker.RealtimeSymbolSource
import org.springframework.stereotype.Component

/**
 * 실시간 구독·분봉 수집 대상 = 앱이 알려 준 관심종목([WatchSymbol]) 계약 테이블 전체. 보유 종목은 모두 관심종목이다(앱이 보유 중인 종목의 삭제를 막는다).
 * 앱의 전략 설정 테이블이나 잔고 원문은 읽지 않는다.
 */
@Component
class WatchSymbolSource(
    private val watch: WatchSymbolRepository,
) : RealtimeSymbolSource {
    override fun symbols(market: Market): List<String> {
        return watch.findAll().filter { it.market == market.name }.map { it.symbol }.distinct()
    }
}

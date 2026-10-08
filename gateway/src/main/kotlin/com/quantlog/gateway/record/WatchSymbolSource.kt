package com.quantlog.gateway.record

import com.quantlog.gateway.broker.Market
import com.quantlog.gateway.broker.RealtimeSymbolSource
import org.springframework.stereotype.Component

/**
 * 실시간 구독·분봉 수집 대상 = 앱이 알려 준 보유 종목([HeldSymbol])과 관심종목([WatchSymbol]) 계약 테이블.
 * 보유 종목이 앞이라 구독 상한을 넘으면 관심종목이 먼저 잘린다. 앱의 전략 설정 테이블이나 잔고 원문은 읽지 않는다.
 */
@Component
class WatchSymbolSource(
    private val held: HeldSymbolRepository,
    private val watch: WatchSymbolRepository,
) : RealtimeSymbolSource {
    override fun symbols(market: Market): List<String> {
        val heldSymbols = held.findAll().filter { it.market == market.name }.map { it.symbol }
        val watched = watch.findAll().filter { it.market == market.name }.map { it.symbol }
        return (heldSymbols + watched).distinct()
    }
}

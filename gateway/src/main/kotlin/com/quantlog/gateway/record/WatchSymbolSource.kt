package com.quantlog.gateway.record

import com.quantlog.gateway.broker.Market
import com.quantlog.gateway.broker.RealtimeSymbolSource
import com.quantlog.gateway.paper.EtfRegistry
import org.springframework.stereotype.Component

/**
 * 실시간 구독·분봉 수집 대상 = 지금 보유 중인 종목(자기 잔고 스냅샷) + 앱이 알려 준 관심종목([WatchSymbol] 계약 테이블).
 * 보유 종목이 앞이라 구독 상한을 넘으면 관심종목이 먼저 잘린다. 앱의 전략 설정 테이블은 읽지 않는다.
 */
@Component
class WatchSymbolSource(
    private val balances: BrokerBalanceRepository,
    private val watch: WatchSymbolRepository,
) : RealtimeSymbolSource, EtfRegistry {
    override fun symbols(market: Market): List<String> {
        val held = balances.findAll().filter { it.market == market.name && it.quantity.signum() > 0 }.map { it.symbol }
        val watched = watch.findAll().filter { it.market == market.name }.map { it.symbol }
        return (held + watched).distinct()
    }

    override fun isEtf(
        market: Market,
        symbol: String,
    ): Boolean = watch.findByMarketAndSymbol(market.name, symbol)?.etf ?: false
}

package com.quantlog.watchlist

import com.quantlog.broker.Market
import com.quantlog.gatewayclient.WatchSymbolRow
import com.quantlog.gatewayclient.WatchSymbolRowRepository
import com.quantlog.strategy.MartingaleProperties
import com.quantlog.strategy.StrategyProperties
import org.springframework.stereotype.Component

/** 종목 설정 행을 새로 만들 때 쓰는 기본값(application.yml 의 quantlog.strategy.*). */
@Component
class SymbolStrategyDefaults(
    private val strategyProperties: StrategyProperties,
    private val martingaleProperties: MartingaleProperties,
    private val watchSymbols: WatchSymbolRowRepository,
) {
    /** [trade] 가 true 면 마틴게일·저점 판단 진입을 켠 채로, false 면 매수 옵션을 모두 끈 채로 만든다. 저장은 호출한 쪽이 한다. */
    fun newRow(
        market: Market,
        symbol: String,
        displayName: String,
        trade: Boolean,
        etf: Boolean,
    ) = SymbolStrategy(
        market = market,
        symbol = symbol,
        displayName = displayName,
        takeProfitPercent = strategyProperties.takeProfitPercent,
        stopLossPercent = strategyProperties.stopLossPercent,
        martingale = trade,
        martingaleDropPercent = martingaleProperties.dropPercent,
        martingaleMultiplier = martingaleProperties.multiplier,
        martingaleMaxStages = martingaleProperties.maxStages,
        legacyEtf = etf,
        supportBounceEntry = trade,
        watchSymbol = watchSymbolOf(market, symbol, etf),
    )

    /** 같은 종목의 `watch_symbol` 행이 이미 있으면 그걸, 없으면 새로 만든다(unique 키 충돌 방지). */
    fun watchSymbolOf(
        market: Market,
        symbol: String,
        etf: Boolean,
    ): WatchSymbolRow =
        watchSymbols.findByMarketAndSymbol(market.name, symbol) ?: watchSymbols.save(WatchSymbolRow(market.name, symbol, etf))
}

package com.quantlog.watchlist

import com.quantlog.broker.Market
import org.mockito.Mockito
import java.math.BigDecimal

/** 테스트용 종목 설정. 마틴게일 값은 운영 기본값(0.5% / 2배 / 5단계 / -3%)과 같다. */
fun symbolStrategy(
    market: Market,
    symbol: String,
    displayName: String = symbol,
    martingale: Boolean = false,
    supportBounceEntry: Boolean = true,
    periodicRebuy: Boolean = false,
    periodicRebuyQuantity: Int = 1,
    periodicRebuyIntervalMinutes: Int = 5,
    supportBounceQuantity: Int = 1,
    takeProfit: String = "0.5",
    stopLoss: String? = null,
) = SymbolStrategy(
    market = market,
    symbol = symbol,
    displayName = displayName,
    takeProfitPercent = BigDecimal(takeProfit),
    stopLossPercent = stopLoss?.let { BigDecimal(it) },
    martingale = martingale,
    martingaleDropPercent = BigDecimal("0.5"),
    martingaleMultiplier = 2,
    martingaleMaxStages = 5,
    supportBounceEntry = supportBounceEntry,
    periodicRebuy = periodicRebuy,
    periodicRebuyQuantity = periodicRebuyQuantity,
    periodicRebuyIntervalMinutes = periodicRebuyIntervalMinutes,
    supportBounceQuantity = supportBounceQuantity,
)

/** 주어진 설정만 담은 저장소. 목록에 없는 종목은 null(행 없음). */
fun symbolStrategyRepositoryOf(vararg configs: SymbolStrategy): SymbolStrategyRepository {
    val repository = Mockito.mock(SymbolStrategyRepository::class.java)
    configs.forEach { Mockito.`when`(repository.findByMarketAndSymbol(it.market, it.symbol)).thenReturn(it) }
    Mockito.`when`(repository.findAll()).thenReturn(configs.toList())
    return repository
}

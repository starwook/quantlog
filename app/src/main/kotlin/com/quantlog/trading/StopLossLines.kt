package com.quantlog.trading

import com.quantlog.broker.Market
import com.quantlog.position.AccountHoldingRepository
import com.quantlog.strategy.FixedPercentExitRule
import com.quantlog.watchlist.SymbolStrategyService
import org.springframework.stereotype.Component
import java.math.BigDecimal

/** 보유 중인 종목의 지금 손절선. 보유가 없거나 손절이 보류면 null. */
fun interface StopLossLines {
    fun of(
        market: Market,
        symbol: String,
    ): BigDecimal?
}

/** 보유 현황(평단)과 종목 설정(손절 %)으로 손절선을 구한다 — 청산([ExitService])과 같은 규칙·같은 대비책을 쓴다. */
@Component
class HoldingStopLossLines(
    private val accountHoldingRepository: AccountHoldingRepository,
    private val symbolStrategyService: SymbolStrategyService,
    private val exitRule: FixedPercentExitRule,
) : StopLossLines {
    override fun of(
        market: Market,
        symbol: String,
    ): BigDecimal? {
        val holding = accountHoldingRepository.findByMarketAndSymbol(market, symbol)?.takeIf { it.quantity > 0 } ?: return null
        val config = symbolStrategyService.find(market, symbol)
        val rule = config?.let { FixedPercentExitRule(it.takeProfitPercent, it.stopLossPercent) } ?: exitRule
        return rule.stopLossLine(holding.avgCost)
    }
}

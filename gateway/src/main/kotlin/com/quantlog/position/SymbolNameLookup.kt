package com.quantlog.position

import com.quantlog.broker.Market
import com.quantlog.watchlist.SymbolStrategyRepository
import org.springframework.stereotype.Component

/**
 * 게이트웨이가 로그·변경 문구에 쓰는 종목 이름. 설정 화면의 서비스(앱)에 기대지 않고 종목 설정 테이블(`symbol_strategy`)을 직접 읽는다.
 * 등록 안 된 종목(증권사 앱에서 산 종목 등)은 코드를 그대로 이름처럼 쓴다.
 */
@Component
class SymbolNameLookup(private val repository: SymbolStrategyRepository) {
    fun displayName(
        market: Market,
        symbol: String,
    ): String = repository.findByMarketAndSymbol(market, symbol)?.displayName?.takeIf { it.isNotBlank() } ?: symbol
}

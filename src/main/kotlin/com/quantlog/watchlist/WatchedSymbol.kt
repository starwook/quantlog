package com.quantlog.watchlist

import com.quantlog.broker.Market
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 우리가 감시하는 종목의 단일 출처(single source of truth). 예전엔 화면 표시용 목록(chart)과
 * 자동매매 대상 목록(trading, application.yml)이 따로 있어서 둘이 어긋났다(2026-09-29: SK하이닉스가
 * 화면엔 있는데 분봉이 안 쌓이던 문제) — 이제 여기 하나로 합친다.
 *
 * - 분봉 수집(EntryScheduler)·차트·백테스트·거래내역 이름 표시는 여기 전체(entries)를 쓴다.
 * - 실제 자동매수 대상만 [autoTradeEnabled] 로 가른다 — 데이터는 다 모으되 매매는 일부만.
 *
 * 고정된 소수 종목이고 런타임에 안 바뀌어서 DB 엔티티가 아니라 enum으로 둔다.
 */
enum class WatchedSymbol(
    val market: Market,
    val symbol: String,
    val displayName: String,
    val autoTradeEnabled: Boolean,
) {
    SAMSUNG(Market.KR, "005930", "삼성전자", autoTradeEnabled = true),
    SK_HYNIX(Market.KR, "000660", "SK하이닉스", autoTradeEnabled = false),
    KODEX_SEMICONDUCTOR(Market.KR, "091160", "KODEX 반도체", autoTradeEnabled = true),
    SOXL(Market.AMEX, "SOXL", "SOXL", autoTradeEnabled = true),
    ;

    companion object {
        fun autoTradeTargets(): List<WatchedSymbol> = entries.filter { it.autoTradeEnabled }

        fun find(
            market: Market,
            symbol: String,
        ): WatchedSymbol? = entries.firstOrNull { it.market == market && it.symbol == symbol }
    }
}

/** 등록 안 된 종목이면(직접 URL로 들어온 경우 등) 코드를 그대로 이름처럼 보여준다. */
fun displayNameOf(
    market: Market,
    symbol: String,
): String = WatchedSymbol.find(market, symbol)?.displayName ?: symbol

/** EntryScheduler 가 주입받는 실제 감시 목록 — 테스트에서는 다른 리스트를 직접 넣어 대체할 수 있다. */
@Configuration
class WatchlistConfig {
    @Bean
    fun watchedSymbols(): List<WatchedSymbol> = WatchedSymbol.entries
}

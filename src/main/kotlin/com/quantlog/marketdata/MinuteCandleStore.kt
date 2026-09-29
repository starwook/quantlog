package com.quantlog.marketdata

import com.quantlog.broker.Market
import com.quantlog.broker.MinuteCandle
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * 분봉 DB 읽기/저장만 한다 — BrokerClient 를 모르기 때문에 KisRealtimeClient 처럼 브로커 구현체보다
 * "아래"에 있어야 하는 컴포넌트도 순환 의존 없이 쓸 수 있다(REST 는 MarketDataService, 실시간은 여기 직접).
 */
@Component
class MinuteCandleStore(private val repository: MinuteCandleRepository) {
    fun saveIfNew(
        market: Market,
        symbol: String,
        candle: MinuteCandle,
    ): MinuteCandleEntity? {
        if (repository.existsByMarketAndSymbolAndTradeDateAndTradeTime(market, symbol, candle.date, candle.time)) return null
        return repository.save(candle.toEntity(market, symbol))
    }

    /** 그날 쌓인 분봉을 오래된 것부터 최신 순으로 돌려준다 (진입 신호 계산용, EntryRule.kt). */
    fun recentCandles(
        market: Market,
        symbol: String,
        date: LocalDate,
    ): List<MinuteCandle> =
        repository
            .findAllByMarketAndSymbolAndTradeDateOrderByTradeTimeDesc(market, symbol, date)
            .asReversed()
            .map { it.toDomain() }

    private fun MinuteCandleEntity.toDomain() =
        MinuteCandle(
            date = tradeDate,
            time = tradeTime,
            open = open,
            high = high,
            low = low,
            close = close,
            volume = volume,
        )

    private fun MinuteCandle.toEntity(
        market: Market,
        symbol: String,
    ) = MinuteCandleEntity(
        market = market,
        symbol = symbol,
        tradeDate = date,
        tradeTime = time,
        open = open,
        high = high,
        low = low,
        close = close,
        volume = volume,
    )
}

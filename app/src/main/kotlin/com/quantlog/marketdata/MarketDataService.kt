package com.quantlog.marketdata

import com.quantlog.broker.Market
import com.quantlog.broker.MinuteCandle
import org.springframework.stereotype.Service
import java.time.LocalDate

/** DB(MinuteCandleStore)에 쌓인 분봉을 읽는다. 쌓는 일은 게이트웨이([MinuteCandleCollector])가 한다. */
@Service
class MarketDataService(
    private val store: MinuteCandleStore,
) {
    fun recentCandles(
        market: Market,
        symbol: String,
        date: LocalDate,
    ): List<MinuteCandle> = store.recentCandles(market, symbol, date)

    fun candleDates(
        market: Market,
        symbol: String,
    ): List<LocalDate> = store.tradeDates(market, symbol)
}

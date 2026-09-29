package com.quantlog.marketdata

import com.quantlog.broker.BrokerClient
import com.quantlog.broker.Market
import com.quantlog.broker.MinuteCandle
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.LocalTime

private val log = KotlinLogging.logger {}

/**
 * REST 로 받은 분봉을 DB(MinuteCandleStore)에 쌓는다. KIS 는 당일 분봉만·1회 최대 30건을 주므로
 * (docs/kis-api/README.md), 호출할 때마다 최근 30건을 받아 새로 생긴 것만 저장한다.
 */
@Service
class MarketDataService(
    private val broker: BrokerClient,
    private val store: MinuteCandleStore,
) {
    fun fetchAndStoreRecentMinutes(
        market: Market,
        symbol: String,
        atTime: LocalTime,
    ): List<MinuteCandleEntity> {
        val candles = broker.minuteCandles(market, symbol, atTime)
        val saved = candles.mapNotNull { store.saveIfNew(market, symbol, it) }
        log.info { "[분봉 저장] $market $symbol: 조회 ${candles.size}건 중 신규 ${saved.size}건 저장" }
        return saved
    }

    fun recentCandles(
        market: Market,
        symbol: String,
        date: LocalDate,
    ): List<MinuteCandle> = store.recentCandles(market, symbol, date)
}

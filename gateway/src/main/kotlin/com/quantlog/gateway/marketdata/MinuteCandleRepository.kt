package com.quantlog.gateway.marketdata

import com.quantlog.gateway.broker.Market
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.LocalDate
import java.time.LocalTime

interface MinuteCandleRepository : JpaRepository<MinuteCandleEntity, Long> {
    fun existsByMarketAndSymbolAndTradeDateAndTradeTime(
        market: Market,
        symbol: String,
        tradeDate: LocalDate,
        tradeTime: LocalTime,
    ): Boolean

    fun findAllByMarketAndSymbolAndTradeDateOrderByTradeTimeDesc(
        market: Market,
        symbol: String,
        tradeDate: LocalDate,
    ): List<MinuteCandleEntity>

    @Query(
        "select distinct c.tradeDate from MinuteCandleEntity c where c.market = :market and c.symbol = :symbol order by c.tradeDate desc",
    )
    fun findTradeDates(
        market: Market,
        symbol: String,
    ): List<LocalDate>
}

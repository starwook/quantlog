package com.quantlog.broker

import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** 종목별 실시간으로 마지막에 본 체결가와 그 시각. */
data class LivePrice(val price: BigDecimal, val at: Instant)

/** 실시간 체결(게이트웨이가 넘긴 한투 시세)에서 읽은 종목별 최신가. [KisBrokerClient.quote] 가 REST 대신 쓴다. */
@Component
class LatestPrices {
    private val prices = ConcurrentHashMap<String, LivePrice>()

    fun record(
        symbol: String,
        price: BigDecimal,
        at: Instant,
    ) {
        prices[symbol] = LivePrice(price, at)
    }

    fun get(symbol: String): LivePrice? = prices[symbol]
}

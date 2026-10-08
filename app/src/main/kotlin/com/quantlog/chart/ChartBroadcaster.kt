package com.quantlog.chart

import com.fasterxml.jackson.databind.ObjectMapper
import com.quantlog.broker.CandleUpdated
import com.quantlog.broker.Market
import com.quantlog.broker.MinuteCandle
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap

/** 종목을 구독 중인 브라우저 세션에 분봉을 실시간으로 중계한다 (게이트웨이의 [CandleUpdated] 이벤트 → 브라우저). */
@Component
class ChartBroadcaster(private val objectMapper: ObjectMapper) {
    private val sessionsByKey = ConcurrentHashMap<String, MutableSet<WebSocketSession>>()

    fun subscribe(
        session: WebSocketSession,
        market: Market,
        symbol: String,
    ) {
        sessionsByKey.getOrPut(key(market, symbol)) { ConcurrentHashMap.newKeySet() }.add(session)
    }

    fun unsubscribeAll(session: WebSocketSession) {
        sessionsByKey.values.forEach { it.remove(session) }
    }

    @EventListener
    fun onCandleUpdated(event: CandleUpdated) = broadcastCandle(event.market, event.symbol, event.candle)

    fun broadcastCandle(
        market: Market,
        symbol: String,
        candle: MinuteCandle,
    ) {
        val sessions = sessionsByKey[key(market, symbol)]
        if (sessions.isNullOrEmpty()) return
        val payload =
            objectMapper.writeValueAsString(
                mapOf(
                    "time" to candle.date.atTime(candle.time).toEpochSecond(ZoneOffset.UTC),
                    "open" to candle.open,
                    "high" to candle.high,
                    "low" to candle.low,
                    "close" to candle.close,
                    "volume" to candle.volume,
                ),
            )
        val message = TextMessage(payload)
        sessions.forEach { session -> runCatching { if (session.isOpen) session.sendMessage(message) } }
    }

    private fun key(
        market: Market,
        symbol: String,
    ) = "$market:$symbol"
}

package com.quantlog.chart

import com.quantlog.broker.Market
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry
import org.springframework.web.socket.handler.TextWebSocketHandler

/** 브라우저가 "SUBSCRIBE KR 005930" 텍스트를 보내면 그 종목 분봉을 실시간으로 받는다(/ws/chart). */
@Component
class ChartWebSocketHandler(private val broadcaster: ChartBroadcaster) : TextWebSocketHandler() {
    override fun handleTextMessage(
        session: WebSocketSession,
        message: TextMessage,
    ) {
        val parts = message.payload.trim().split(" ")
        if (parts.size != 3 || parts[0] != "SUBSCRIBE") return
        val market = runCatching { Market.valueOf(parts[1]) }.getOrNull() ?: return
        broadcaster.subscribe(session, market, parts[2])
    }

    override fun afterConnectionClosed(
        session: WebSocketSession,
        status: CloseStatus,
    ) {
        broadcaster.unsubscribeAll(session)
    }
}

@Configuration
@EnableWebSocket
class ChartWebSocketConfig(private val handler: ChartWebSocketHandler) : WebSocketConfigurer {
    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        registry.addHandler(handler, "/ws/chart").setAllowedOrigins("*")
    }
}

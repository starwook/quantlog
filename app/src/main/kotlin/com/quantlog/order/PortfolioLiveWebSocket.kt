package com.quantlog.order

import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry
import org.springframework.web.socket.handler.TextWebSocketHandler

@Component
class PortfolioLiveWebSocketHandler(private val broadcaster: PortfolioLiveBroadcaster) : TextWebSocketHandler() {
    override fun afterConnectionEstablished(session: WebSocketSession) = broadcaster.connect(session)

    override fun afterConnectionClosed(
        session: WebSocketSession,
        status: CloseStatus,
    ) = broadcaster.disconnect(session)
}

@Configuration
@EnableWebSocket
class PortfolioLiveWebSocketConfig(private val handler: PortfolioLiveWebSocketHandler) : WebSocketConfigurer {
    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        registry.addHandler(handler, "/ws/portfolio").setAllowedOrigins("*")
    }
}

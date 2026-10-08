package com.quantlog.gateway.stream

import com.quantlog.gateway.GatewayProperties
import com.quantlog.gateway.api.TokenInterceptor
import org.springframework.context.annotation.Configuration
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketHandler
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry
import org.springframework.web.socket.handler.TextWebSocketHandler
import org.springframework.web.socket.server.HandshakeInterceptor

@Component
class StreamHandler(private val hub: StreamHub) : TextWebSocketHandler() {
    override fun afterConnectionEstablished(session: WebSocketSession) = hub.register(session)

    override fun afterConnectionClosed(
        session: WebSocketSession,
        status: CloseStatus,
    ) = hub.unregister(session)

    /** 앱은 스트림으로 아무것도 보내지 않는다(명령은 HTTP). 받은 메시지는 무시한다. */
    override fun handleTextMessage(
        session: WebSocketSession,
        message: TextMessage,
    ) = Unit
}

@Configuration
@EnableWebSocket
class StreamWebSocketConfig(
    private val handler: StreamHandler,
    private val properties: GatewayProperties,
) : WebSocketConfigurer {
    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        registry.addHandler(handler, "/stream").addInterceptors(TokenHandshake(properties)).setAllowedOrigins("*")
    }

    /** HTTP 와 같은 공유 토큰 검사. */
    private class TokenHandshake(private val properties: GatewayProperties) : HandshakeInterceptor {
        override fun beforeHandshake(
            request: ServerHttpRequest,
            response: ServerHttpResponse,
            wsHandler: WebSocketHandler,
            attributes: MutableMap<String, Any>,
        ): Boolean = properties.token.isBlank() || request.headers.getFirst(TokenInterceptor.TOKEN_HEADER) == properties.token

        override fun afterHandshake(
            request: ServerHttpRequest,
            response: ServerHttpResponse,
            wsHandler: WebSocketHandler,
            exception: Exception?,
        ) = Unit
    }
}

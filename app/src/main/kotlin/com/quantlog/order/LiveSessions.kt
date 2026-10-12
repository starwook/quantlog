package com.quantlog.order

import com.fasterxml.jackson.databind.ObjectMapper
import mu.KotlinLogging
import org.springframework.stereotype.Component
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import java.util.concurrent.CopyOnWriteArraySet

private val log = KotlinLogging.logger {}

/** 주문 화면에 연결된 브라우저 세션 모음. 메시지를 JSON 으로 만들어 보낸다. */
@Component
class LiveSessions(private val objectMapper: ObjectMapper) {
    private val sessions = CopyOnWriteArraySet<WebSocketSession>()

    fun isEmpty() = sessions.isEmpty()

    fun add(session: WebSocketSession) {
        sessions.add(session)
    }

    fun remove(session: WebSocketSession) {
        sessions.remove(session)
    }

    fun broadcast(payload: Any) = sessions.forEach { send(it, payload) }

    /** 체결통보 수신 스레드 등 여러 스레드가 부르므로 세션별로 직렬화한다(WebSocketSession 은 동시 전송이 안전하지 않다). */
    fun send(
        session: WebSocketSession,
        payload: Any,
    ) {
        val text = TextMessage(objectMapper.writeValueAsString(payload))
        runCatching { synchronized(session) { if (session.isOpen) session.sendMessage(text) } }
            .onFailure {
                log.warn { "[주문 화면] 전송 실패, 세션을 뺀다: ${it.message}" }
                sessions.remove(session)
            }
    }
}

package com.quantlog.gatewayclient

import com.fasterxml.jackson.databind.ObjectMapper
import mu.KotlinLogging
import org.springframework.context.ApplicationEventPublisher
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketHttpHeaders
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.handler.TextWebSocketHandler
import java.net.URI
import java.time.Duration
import java.time.Instant

private val log = KotlinLogging.logger {}

/** 게이트웨이가 "원장에 새 행이 생겼다"고 알렸다. 정본은 DB 라 이 알림은 처리를 서두르게 할 뿐이다. */
data class GatewayNotified(val kind: String)

/**
 * 게이트웨이 → 앱 스트림(웹소켓) 수신. 형식은 docs/contracts/gateway-stream.md.
 * 시세(`raw`)는 한투 메시지 원문이라 [KisRealtimeTickParser] 로 해석하고, [RealtimeCandleBuilder] 가 PriceTick·CandleUpdated 이벤트를 낸다(화면·청산·마틴게일이 그대로 쓴다).
 * 체결(`fill`)·잔고(`balance`) 알림은 [GatewayNotified] 로 발행한다. 끊기면 3초마다 다시 붙고, 20초 넘게 아무 메시지(ping 포함)가 없어도 끊고 다시 붙는다.
 * 스트림이 끊겨 있어도 매매 흐름은 DB·HTTP 로 이어진다 — 시세만 폴링으로 돌아간다.
 */
@Component
class GatewayStreamClient(
    private val properties: GatewayClientProperties,
    private val objectMapper: ObjectMapper,
    private val events: ApplicationEventPublisher,
    private val candles: RealtimeCandleBuilder,
) : TextWebSocketHandler() {
    @Volatile private var session: WebSocketSession? = null

    @Volatile private var lastMessageAt: Instant = Instant.EPOCH

    @Volatile private var connecting = false

    val connected: Boolean get() = session?.isOpen == true

    @Scheduled(fixedDelay = 3_000, initialDelay = 1_000)
    fun keepConnected() {
        if (!properties.enabled) return
        val current = session
        if (current != null && current.isOpen) {
            if (Duration.between(lastMessageAt, Instant.now()) > SILENCE_LIMIT) {
                log.warn { "[게이트웨이 스트림] ${SILENCE_LIMIT.seconds}초 넘게 메시지가 없어 다시 연결한다" }
                runCatching { current.close(CloseStatus.SESSION_NOT_RELIABLE) }
            }
            return
        }
        if (connecting) return
        connecting = true
        try {
            val headers = WebSocketHttpHeaders()
            if (properties.token.isNotBlank()) headers.set("X-Gateway-Token", properties.token)
            val uri = URI.create(properties.baseUrl.replaceFirst("http", "ws").trimEnd('/') + "/stream")
            StandardWebSocketClient().execute(this, headers, uri).whenComplete { _, error ->
                connecting = false
                if (error != null) log.debug { "[게이트웨이 스트림] 연결 실패: ${error.message}" }
            }
        } catch (e: Exception) {
            connecting = false
            log.debug { "[게이트웨이 스트림] 연결 시도 실패: ${e.message}" }
        }
    }

    override fun afterConnectionEstablished(session: WebSocketSession) {
        this.session = session
        lastMessageAt = Instant.now()
        connecting = false
        log.info { "[게이트웨이 스트림] 연결됨" }
    }

    override fun afterConnectionClosed(
        session: WebSocketSession,
        status: CloseStatus,
    ) {
        if (this.session?.id == session.id) this.session = null
        log.info { "[게이트웨이 스트림] 끊김: $status" }
    }

    override fun handleTransportError(
        session: WebSocketSession,
        exception: Throwable,
    ) {
        log.warn { "[게이트웨이 스트림] 전송 오류: ${exception.message}" }
        runCatching { session.close(CloseStatus.SESSION_NOT_RELIABLE) }
    }

    override fun handleTextMessage(
        session: WebSocketSession,
        message: TextMessage,
    ) {
        lastMessageAt = Instant.now()
        try {
            handle(message.payload)
        } catch (e: Exception) {
            log.warn(e) { "[게이트웨이 스트림] 메시지 처리 실패(무시): ${message.payload.take(200)}" }
        }
    }

    /** 테스트에서도 쓴다: 스트림 메시지 한 건(JSON)을 해석해 이벤트로 내보낸다. */
    fun handle(json: String) {
        val node = objectMapper.readTree(json)
        when (node.path("type").asText()) {
            "raw" -> KisRealtimeTickParser.parse(node.path("data").asText()).forEach(candles::onTrade)
            "fill", "balance" -> events.publishEvent(GatewayNotified(node.path("type").asText()))
            "hello" -> log.info { "[게이트웨이 스트림] hello 인스턴스=${node.path("instanceId").asText()} 계약=${node.path("contractVersion").asText()}" }
            else -> Unit // ping 등
        }
    }

    private companion object {
        val SILENCE_LIMIT: Duration = Duration.ofSeconds(20)
    }
}

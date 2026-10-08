package com.quantlog.gateway.stream

import com.fasterxml.jackson.databind.ObjectMapper
import com.quantlog.gateway.kis.RawRealtimeMessage
import com.quantlog.gateway.lease.CONTRACT_VERSION
import com.quantlog.gateway.lease.LeaseService
import jakarta.annotation.PreDestroy
import mu.KotlinLogging
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private val log = KotlinLogging.logger {}

/**
 * 게이트웨이 → 앱 실시간 스트림(웹소켓 `/stream`, JSON). 메시지는 모두 `{"type": ..., ...}` 이고 형식은 docs/contracts/gateway-stream.md 가 기준이다.
 * - 시세(`raw`)는 한투 메시지 원문을 **해석 없이** 그대로 보낸다(틱·분봉 해석은 앱). 분봉 거래량 합산에 모든 틱이 필요해 묶지 않는다.
 *   느린 앱이 증권사 수신 스레드를 막지 못하게, 보내기 대기가 [RAW_BACKLOG_LIMIT] 건을 넘으면 새 시세는 버린다.
 * - 체결 알림(`fill`)·잔고 알림(`balance`)은 빠짐없이 보낸다. 다만 정본은 DB 원장이고 이 메시지는 "새 행이 생겼다"는 빠른 알림일 뿐이다
 *   (앱이 꺼져 있었다면 DB 에서 마지막으로 처리한 ID 이후를 읽어 따라잡는다).
 * 모든 전송은 전용 스레드 하나에서 한다 — 연결마다 보내기 시간·버퍼 상한이 있어 느린 연결은 끊긴다.
 */
@Component
class StreamHub(
    private val objectMapper: ObjectMapper,
    private val lease: LeaseService,
) {
    private val sessions = ConcurrentHashMap<String, WebSocketSession>()
    private val rawBacklog = AtomicInteger()
    private val executor =
        Executors.newSingleThreadScheduledExecutor { Thread(it, "gateway-stream").apply { isDaemon = true } }

    init {
        executor.scheduleWithFixedDelay({
            safely { send("ping", mapOf("at" to Instant.now().toString())) }
        }, PING_SECONDS, PING_SECONDS, TimeUnit.SECONDS)
    }

    fun register(session: WebSocketSession) {
        val decorated = ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MILLIS, BUFFER_LIMIT_BYTES)
        sessions[session.id] = decorated
        executor.execute {
            safely {
                sendTo(
                    decorated,
                    "hello",
                    mapOf(
                        "instanceId" to lease.instanceId,
                        "startedAt" to lease.startedAt.toString(),
                        "contractVersion" to CONTRACT_VERSION,
                    ),
                )
            }
        }
    }

    fun unregister(session: WebSocketSession) {
        sessions.remove(session.id)
    }

    @EventListener
    fun onRaw(message: RawRealtimeMessage) {
        if (sessions.isEmpty()) return
        if (rawBacklog.incrementAndGet() > RAW_BACKLOG_LIMIT) {
            rawBacklog.decrementAndGet()
            return
        }
        executor.execute {
            rawBacklog.decrementAndGet()
            safely { send("raw", mapOf("data" to message.raw)) }
        }
    }

    fun fillRecorded(id: Long) = executor.execute { safely { send("fill", mapOf("id" to id)) } }

    fun balanceRecorded(seq: Long) = executor.execute { safely { send("balance", mapOf("seq" to seq)) } }

    val connectionCount: Int get() = sessions.size

    private fun send(
        type: String,
        payload: Map<String, Any?>,
    ) {
        if (sessions.isEmpty()) return
        val json = objectMapper.writeValueAsString(mapOf("type" to type) + payload)
        sessions.values.toList().forEach { sendRaw(it, json) }
    }

    private fun sendTo(
        session: WebSocketSession,
        type: String,
        payload: Map<String, Any?>,
    ) = sendRaw(session, objectMapper.writeValueAsString(mapOf("type" to type) + payload))

    private fun sendRaw(
        session: WebSocketSession,
        json: String,
    ) {
        try {
            if (session.isOpen) session.sendMessage(TextMessage(json))
        } catch (e: Exception) {
            log.warn { "[스트림] 연결 ${session.id} 전송 실패 — 끊는다: ${e.message}" }
            sessions.remove(session.id)
            runCatching { session.close(CloseStatus.SESSION_NOT_RELIABLE) }
        }
    }

    private fun safely(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            log.warn(e) { "[스트림] 전송 처리 실패" }
        }
    }

    @PreDestroy
    fun stop() {
        executor.shutdownNow()
        sessions.values.forEach { runCatching { it.close(CloseStatus.GOING_AWAY) } }
    }

    private companion object {
        const val RAW_BACKLOG_LIMIT = 5_000
        const val PING_SECONDS = 5L
        const val SEND_TIME_LIMIT_MILLIS = 2_000
        const val BUFFER_LIMIT_BYTES = 256 * 1024
    }
}

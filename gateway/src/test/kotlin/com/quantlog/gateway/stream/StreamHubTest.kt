package com.quantlog.gateway.stream

import com.fasterxml.jackson.databind.ObjectMapper
import com.quantlog.gateway.kis.KisConnectionChanged
import com.quantlog.gateway.lease.LeaseService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/** 한투 연결 상태 알림(`kis_ws`)의 형식 — docs/contracts/README.md 2절. 앱 `GatewayStreamClientTest` 와 같은 모양이어야 한다. */
class StreamHubTest {
    private val mapper = ObjectMapper()
    private val lease =
        Mockito.mock(LeaseService::class.java).also {
            Mockito.`when`(it.instanceId).thenReturn("gw-1")
            Mockito.`when`(it.startedAt).thenReturn(Instant.parse("2026-10-14T00:00:00Z"))
        }
    private val hub = StreamHub(mapper, lease)
    private val sent = CopyOnWriteArrayList<String>()

    @AfterEach
    fun tearDown() = hub.stop()

    private fun session(id: String): WebSocketSession {
        val session = Mockito.mock(WebSocketSession::class.java)
        Mockito.`when`(session.id).thenReturn(id)
        Mockito.`when`(session.isOpen).thenReturn(true)
        Mockito.doAnswer {
            sent += it.getArgument<TextMessage>(0).payload
            null
        }.`when`(session).sendMessage(Mockito.any())
        return session
    }

    private fun waitFor(condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 2_000
        while (!condition() && System.currentTimeMillis() < until) Thread.sleep(10)
    }

    @Test
    fun `한투 연결 상태가 바뀌면 kis_ws 로 그대로 알리고 새로 붙은 앱에는 hello 뒤에 지금 상태를 보낸다`() {
        hub.register(session("a"))
        waitFor { sent.any { it.contains("\"hello\"") } }

        val downSince = Instant.parse("2026-10-14T02:00:02Z")
        hub.onKisConnection(KisConnectionChanged(true, Instant.parse("2026-10-14T02:00:02.300Z"), downSince))
        waitFor { sent.any { it.contains("kis_ws") } }

        val message = mapper.readTree(sent.first { it.contains("kis_ws") })
        assertEquals("kis_ws", message.path("type").asText())
        assertTrue(message.path("fillNoticeLive").asBoolean())
        assertEquals("2026-10-14T02:00:02.300Z", message.path("at").asText())
        assertEquals("2026-10-14T02:00:02Z", message.path("downSince").asText())

        sent.clear()
        hub.register(session("b"))
        waitFor { sent.any { it.contains("kis_ws") } }
        val types = sent.map { mapper.readTree(it).path("type").asText() }
        assertEquals(listOf("hello", "kis_ws"), types.filter { it != "ping" })
    }
}

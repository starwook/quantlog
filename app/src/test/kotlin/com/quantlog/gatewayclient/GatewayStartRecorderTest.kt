package com.quantlog.gatewayclient

import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GatewayStartRecorderTest {
    private val now = Instant.parse("2026-10-13T21:21:00Z")
    private val starts = Mockito.mock(GatewayStartRepository::class.java)
    private val saved = mutableListOf<GatewayStart>()

    init {
        Mockito.doAnswer {
            saved += it.getArgument<GatewayStart>(0)
            it.getArgument<GatewayStart>(0)
        }.`when`(starts).save(Mockito.any<GatewayStart>() ?: GatewayStart("", now, null, now))
    }

    private fun recorder() = GatewayStartRecorder(starts, Clock.fixed(now, ZoneOffset.UTC))

    private fun row(
        instanceId: String,
        startedAt: Instant,
        heartbeatAt: Instant,
    ) = GatewayInstanceRow(instanceId = instanceId, startedAt = startedAt, heartbeatAt = heartbeatAt)

    @Test
    fun `인스턴스가 바뀌면 새 시작 시각과 직전 인스턴스의 마지막 하트비트를 한 번만 적는다`() {
        val recorder = recorder()
        Mockito.`when`(starts.existsByInstanceId("g1")).thenReturn(true)
        val oldStart = Instant.parse("2026-10-12T09:00:00Z")
        val newStart = Instant.parse("2026-10-13T21:20:48Z")

        recorder.observe(row("g1", oldStart, Instant.parse("2026-10-13T21:20:29Z")))
        recorder.observe(row("g1", oldStart, Instant.parse("2026-10-13T21:20:31Z")))
        recorder.observe(row("g2", newStart, Instant.parse("2026-10-13T21:20:49Z")))
        recorder.observe(row("g2", newStart, Instant.parse("2026-10-13T21:20:51Z")))

        assertEquals(listOf("g2"), saved.map { it.instanceId })
        assertEquals(newStart, saved.single().startedAt)
        assertEquals(Instant.parse("2026-10-13T21:20:31Z"), saved.single().previousHeartbeatAt)
        assertEquals(now, saved.single().detectedAt)
    }

    @Test
    fun `앱이 꺼져 있던 사이 새로 뜬 인스턴스도 적되 직전 하트비트는 모른다`() {
        recorder().observe(row("g2", Instant.parse("2026-10-13T21:20:48Z"), Instant.parse("2026-10-13T21:20:59Z")))

        assertEquals(listOf("g2"), saved.map { it.instanceId })
        assertNull(saved.single().previousHeartbeatAt)
    }

    @Test
    fun `앱이 다시 떠도 이미 적은 인스턴스는 또 적지 않는다`() {
        Mockito.`when`(starts.existsByInstanceId("g2")).thenReturn(true)

        recorder().observe(row("g2", Instant.parse("2026-10-13T21:20:48Z"), Instant.parse("2026-10-13T21:20:59Z")))

        assertTrue(saved.isEmpty())
    }
}

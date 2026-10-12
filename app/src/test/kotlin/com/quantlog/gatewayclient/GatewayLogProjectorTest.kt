package com.quantlog.gatewayclient

import com.quantlog.errorlog.ErrorLogRecorder
import com.quantlog.errorlog.LogRecord
import com.quantlog.notification.Notifier
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.Instant
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GatewayLogProjectorTest {
    private val rows = Mockito.mock(GatewayLogRowRepository::class.java)
    private val cursors = Mockito.mock(ProjectionCursorRepository::class.java)
    private val recorder = Mockito.mock(ErrorLogRecorder::class.java)
    private val notifier = Mockito.mock(Notifier::class.java)
    private val projector = GatewayLogProjector(rows, cursors, recorder, notifier)

    private val recorded = mutableListOf<LogRecord>()
    private val savedCursors = mutableListOf<Long>()
    private val notifications = mutableListOf<String>()

    init {
        Mockito.doAnswer {
            recorded += it.getArgument<LogRecord>(0)
            null
        }.`when`(recorder).record(anyNonNull())
        Mockito.doAnswer {
            savedCursors += it.getArgument<ProjectionCursor>(0).lastId
            null
        }.`when`(cursors).save(anyNonNull<ProjectionCursor>())
        Mockito.doAnswer {
            notifications += it.getArgument<String>(0)
            null
        }.`when`(notifier).send(anyNonNull())
    }

    private fun <T> anyNonNull(): T = Mockito.any<T>() as T

    private fun row(
        id: Long,
        message: String,
    ) = GatewayLogRow(id, Instant.parse("2026-10-12T00:00:00Z"), "ERROR", "com.quantlog.gateway.kis.KisApiClient", message)

    @Test
    fun `커서가 없으면 지금 마지막 줄부터 시작하고 옛 로그는 반영하지 않는다`() {
        Mockito.`when`(cursors.findById("gateway_log")).thenReturn(Optional.empty())
        Mockito.`when`(rows.maxId()).thenReturn(7L)

        assertEquals(0, projector.project())

        assertEquals(listOf(7L), savedCursors)
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun `새 줄은 오류 기록에 넣고 커서를 옮기며, 같은 종류 알림은 한 번만 보낸다`() {
        Mockito.`when`(cursors.findById("gateway_log")).thenReturn(Optional.of(ProjectionCursor("gateway_log", 1)))
        Mockito.`when`(notifier.enabled).thenReturn(true)
        Mockito.`when`(rows.findTop100ByIdGreaterThanOrderByIdAsc(1L))
            .thenReturn(listOf(row(2, "[KIS 오류] 대기 100ms"), row(3, "[KIS 오류] 대기 200ms")))
        Mockito.`when`(rows.findTop100ByIdGreaterThanOrderByIdAsc(3L)).thenReturn(emptyList())

        assertEquals(2, projector.project())

        assertEquals(listOf("[KIS 오류] 대기 100ms", "[KIS 오류] 대기 200ms"), recorded.map { it.message })
        assertEquals(1, notifications.size)
        assertEquals(listOf(2L, 3L), savedCursors)
    }
}

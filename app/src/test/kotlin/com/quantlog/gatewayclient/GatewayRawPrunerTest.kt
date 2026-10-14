package com.quantlog.gatewayclient

import com.quantlog.errorlog.ErrorLogProperties
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import kotlin.test.assertEquals

class GatewayRawPrunerTest {
    private val charts = Mockito.mock(KisMinuteChartRowRepository::class.java)
    private val balances = Mockito.mock(KisBalanceRowRepository::class.java)
    private val logs = Mockito.mock(GatewayLogRowRepository::class.java)
    private val cursors = Mockito.mock(ProjectionCursorRepository::class.java)
    private val now = Instant.parse("2026-10-14T13:00:00Z")
    private val pruner =
        GatewayRawPruner(charts, balances, logs, cursors, ErrorLogProperties(retentionDays = 14), Clock.fixed(now, ZoneOffset.UTC))

    private fun cursor(
        name: String,
        lastId: Long,
    ) = Mockito.`when`(cursors.findById(name)).thenReturn(Optional.of(ProjectionCursor(name, lastId)))

    @Test
    fun `분봉은 하루 지난 줄 중 앱이 읽은 줄까지만, 잔고는 가장 큰 id 한 줄을 남기고, 게이트웨이 로그는 14일 지난 읽은 줄만 지운다`() {
        cursor("kis_minute_chart", 500)
        cursor("gateway_log", 70)
        Mockito.`when`(balances.findTopByOrderByIdDesc()).thenReturn(KisBalanceRow(id = 90))
        Mockito.`when`(charts.deleteReadBefore(now.minusSeconds(86_400), 500)).thenReturn(3)
        Mockito.`when`(balances.deleteOlderBefore(now.minusSeconds(86_400), 90)).thenReturn(2)
        Mockito.`when`(logs.deleteReadBefore(now.minusSeconds(14 * 86_400), 70)).thenReturn(1)

        assertEquals(Triple(3, 2, 1), pruner.prune())
    }

    @Test
    fun `앱이 아직 한 줄도 읽지 않았으면(커서 없음) 분봉·게이트웨이 로그는 지우지 않는다`() {
        Mockito.`when`(cursors.findById(Mockito.anyString())).thenReturn(Optional.empty())
        Mockito.`when`(balances.findTopByOrderByIdDesc()).thenReturn(null)

        assertEquals(Triple(0, 0, 0), pruner.prune())
        Mockito.verifyNoInteractions(charts, logs)
    }

    @Test
    fun `한 테이블 삭제가 실패해도 나머지는 지운다`() {
        cursor("kis_minute_chart", 500)
        cursor("gateway_log", 70)
        Mockito.`when`(balances.findTopByOrderByIdDesc()).thenReturn(KisBalanceRow(id = 90))
        Mockito.`when`(charts.deleteReadBefore(now.minusSeconds(86_400), 500)).thenThrow(IllegalStateException("boom"))
        Mockito.`when`(balances.deleteOlderBefore(now.minusSeconds(86_400), 90)).thenReturn(2)
        Mockito.`when`(logs.deleteReadBefore(now.minusSeconds(14 * 86_400), 70)).thenReturn(1)

        assertEquals(Triple(0, 2, 1), pruner.prune())
    }
}

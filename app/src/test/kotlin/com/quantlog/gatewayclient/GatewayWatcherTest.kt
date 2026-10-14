package com.quantlog.gatewayclient

import com.quantlog.broker.Market
import com.quantlog.position.FillReconcileRequested
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional

class GatewayWatcherTest {
    private val now = Instant.parse("2026-10-08T01:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val rows = Mockito.mock(GatewayInstanceRowRepository::class.java)
    private val published = mutableListOf<Any>()
    private val watcher =
        GatewayWatcher(
            rows,
            { published += it },
            GatewayClientProperties(),
            Mockito.mock(GatewayStartRecorder::class.java),
            clock,
        )

    private fun row(
        heartbeatAgo: Duration,
        wsConnected: Boolean = true,
        live: String = "005930,000660",
    ) = GatewayInstanceRow(
        instanceId = "g1",
        startedAt = now.minusSeconds(3600),
        heartbeatAt = now.minus(heartbeatAgo),
        leaseUntil = now.plusSeconds(30),
        wsConnectedAt = if (wsConnected) now.minusSeconds(60) else null,
        liveSymbols = live,
        contractVersion = APP_CONTRACT_VERSION,
    )

    private fun check(row: GatewayInstanceRow?) {
        Mockito.`when`(rows.findById(1L)).thenReturn(Optional.ofNullable(row))
        watcher.check()
    }

    @Test
    fun `하트비트가 신선하고 웹소켓이 붙어 있으면 구독 종목만 실시간으로 본다`() {
        check(row(Duration.ofSeconds(2)))

        assertTrue(watcher.isLive(Market.KR, "005930"))
        assertFalse(watcher.isLive(Market.KR, "035720"))
    }

    @Test
    fun `하트비트가 오래됐거나 웹소켓이 끊겼거나 행이 없으면 실시간이 아니다`() {
        check(row(Duration.ofSeconds(60)))
        assertFalse(watcher.isLive(Market.KR, "005930"))

        check(row(Duration.ofSeconds(2), wsConnected = false))
        assertFalse(watcher.isLive(Market.KR, "005930"))

        check(null)
        assertFalse(watcher.isLive(Market.KR, "005930"))
    }

    @Test
    fun `증권사 웹소켓이 새로 붙은 걸 보면 거래를 멈추고 체결조회로 맞추라고 요청한다`() {
        check(row(Duration.ofSeconds(2)))
        assertEquals(listOf(FillReconcileRequested("게이트웨이 실시간 연결 감지(하트비트)", halting = true)), published)

        // 같은 연결이면 다시 요청하지 않는다.
        check(row(Duration.ofSeconds(2)))
        assertEquals(1, published.size)
    }
}

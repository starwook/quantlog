package com.quantlog.gatewayclient

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.quantlog.marketdata.MinuteCandleEntity
import com.quantlog.marketdata.MinuteCandleRepository
import com.quantlog.marketdata.MinuteCandleStore
import com.quantlog.position.HoldingSyncService
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.Instant
import java.time.LocalTime
import java.util.Optional
import kotlin.test.assertEquals

/** 게이트웨이가 원문 그대로 쌓은 잔고·분봉(`kis_balance`, `kis_minute_chart`)을 앱이 한투 문서대로 풀어 쓰는 쪽. */
class RawProjectorsTest {
    private val cursorStore = mutableMapOf<String, Long>()
    private val cursors =
        Mockito.mock(ProjectionCursorRepository::class.java).also { repo ->
            Mockito.`when`(repo.findById(Mockito.anyString())).thenAnswer {
                Optional.ofNullable(cursorStore[it.getArgument<String>(0)]?.let { v -> ProjectionCursor("x", v) })
            }
            Mockito.`when`(repo.save(Mockito.any(ProjectionCursor::class.java))).thenAnswer {
                it.getArgument<ProjectionCursor>(0).also { c -> cursorStore[c.name] = c.lastId }
            }
        }
    private val mapper = jacksonObjectMapper()

    private val fetchedAt = Instant.parse("2026-10-08T01:00:00Z")

    private fun balanceRow(
        id: Long,
        body: String,
    ) = KisBalanceRow(id, fetchedAt, fetchedAt, body)

    @Test
    fun `새 잔고 줄이면 한투 문서대로 읽어 보유 현황에 맞추고 보유 종목을 게이트웨이에 알린다`() {
        val balances = Mockito.mock(KisBalanceRowRepository::class.java)
        Mockito.`when`(balances.findTopByOrderByIdDesc()).thenReturn(
            balanceRow(
                7,
                """{"rt_cd":"0","output1":[{"pdno":"005930","prdt_name":"삼성전자","hldg_qty":"2","pchs_avg_pric":"70000","prpr":"70500"}]}""",
            ),
        )
        val holdingSync = Mockito.mock(HoldingSyncService::class.java)
        val projector = BalanceProjector(balances, cursors, holdingSync, mapper)

        assertEquals(true, projector.project())

        Mockito.verify(holdingSync).sync(Mockito.anyList(), Mockito.eq(fetchedAt) ?: fetchedAt)
        assertEquals(7L, cursorStore["kis_balance"])
        assertEquals(false, projector.project()) // 같은 줄은 다시 반영하지 않는다
        Mockito.verifyNoMoreInteractions(holdingSync)
    }

    @Test
    fun `읽을 수 없는 잔고 줄은 건너뛰고 커서만 옮긴다`() {
        val balances = Mockito.mock(KisBalanceRowRepository::class.java)
        Mockito.`when`(balances.findTopByOrderByIdDesc()).thenReturn(balanceRow(3, "not json"))
        val holdingSync = Mockito.mock(HoldingSyncService::class.java)

        assertEquals(false, BalanceProjector(balances, cursors, holdingSync, mapper).project())

        Mockito.verifyNoInteractions(holdingSync)
        assertEquals(3L, cursorStore["kis_balance"])
    }

    @Test
    fun `분봉 원문은 종목별 가장 최근 줄만 풀어 새 분봉을 저장한다`() {
        fun chart(
            id: Long,
            symbol: String,
            vararg hours: String,
        ) = KisMinuteChartRow(
            id,
            "KR",
            symbol,
            fetchedAt,
            """{"rt_cd":"0","output2":[${hours.joinToString(",") {
                """{"stck_bsop_date":"20261008","stck_cntg_hour":"$it","stck_prpr":"100","stck_oprc":"100",""" +
                    """"stck_hgpr":"100","stck_lwpr":"100","cntg_vol":"1"}"""
            }}]}""",
        )
        val charts = Mockito.mock(KisMinuteChartRowRepository::class.java)
        Mockito.`when`(charts.findTop200ByIdGreaterThanOrderByIdAsc(0)).thenReturn(
            listOf(chart(1, "005930", "090000"), chart(2, "005930", "090100", "090000"), chart(3, "000660", "090000")),
        )
        Mockito.`when`(charts.findTop200ByIdGreaterThanOrderByIdAsc(3)).thenReturn(emptyList())
        val saved = mutableListOf<MinuteCandleEntity>()
        val repository =
            Mockito.mock(MinuteCandleRepository::class.java).also { repo ->
                Mockito.`when`(repo.save(Mockito.any(MinuteCandleEntity::class.java))).thenAnswer {
                    (it.arguments[0] as MinuteCandleEntity).also(saved::add)
                }
            }
        val projector = CandleProjector(charts, cursors, MinuteCandleStore(repository), mapper, GatewayClientProperties())

        assertEquals(3, projector.project())

        // 005930 은 가장 최근 줄(id 2)의 2건, 000660 은 1건 — id 1 의 오래된 줄은 풀지 않는다.
        assertEquals(
            setOf("005930" to LocalTime.of(9, 1), "005930" to LocalTime.of(9, 0), "000660" to LocalTime.of(9, 0)),
            saved.map {
                it.symbol to it.tradeTime
            }.toSet(),
        )
        assertEquals(3L, cursorStore["kis_minute_chart"])
    }
}

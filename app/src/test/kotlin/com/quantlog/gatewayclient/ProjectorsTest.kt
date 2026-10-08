package com.quantlog.gatewayclient

import com.quantlog.broker.FillNotice
import com.quantlog.position.HoldingSyncService
import com.quantlog.position.TradeService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.SimpleTransactionStatus
import java.math.BigDecimal
import java.time.Instant
import java.util.Optional

class ProjectorsTest {
    private val cursorStore = mutableMapOf<String, Long>()
    private val cursors =
        Mockito.mock(ProjectionCursorRepository::class.java).also { repo ->
            Mockito.`when`(repo.findById(Mockito.anyString())).thenAnswer {
                Optional.ofNullable(
                    cursorStore[it.getArgument<String>(0)]?.let {
                            v ->
                        ProjectionCursor("x", v)
                    },
                )
            }
            Mockito.`when`(repo.save(Mockito.any(ProjectionCursor::class.java))).thenAnswer {
                it.getArgument<ProjectionCursor>(0).also { c -> cursorStore[c.name] = c.lastId }
            }
        }
    private val tx =
        Mockito.mock(PlatformTransactionManager::class.java).also {
            Mockito.`when`(it.getTransaction(Mockito.any())).thenReturn(SimpleTransactionStatus())
        }
    private val fills = Mockito.mock(BrokerFillRowRepository::class.java)
    private val holdingSync = Mockito.mock(HoldingSyncService::class.java)
    private val tradeService = Mockito.mock(TradeService::class.java)
    private val projector = FillProjector(fills, cursors, holdingSync, tradeService, tx)

    private val receivedAt = Instant.parse("2026-10-08T01:00:00Z")

    private fun row(
        id: Long,
        filledFlag: String = "2",
    ) = BrokerFillRow(
        id = id, receivedAt = receivedAt, symbol = "005930", orderNo = "O$id", sellBuyCode = "02", filledFlag = filledFlag,
        filledQuantity =
            BigDecimal(
                "2",
            ),
        filledPrice = BigDecimal("70100"), orderQuantity = BigDecimal("2"), orderPrice = BigDecimal("70200"),
        noticeTime = "093000",
    )

    @Test
    fun `커서가 없으면 지금 마지막 ID 로 시작하고 이전 체결은 다시 처리하지 않는다`() {
        Mockito.`when`(fills.maxId()).thenReturn(40L)

        assertEquals(0, projector.project())
        assertEquals(40L, cursorStore["fill"])
        Mockito.verifyNoInteractions(holdingSync)
    }

    private val applied = mutableListOf<Triple<FillNotice, Long, Instant>>()
    private val accepted = mutableListOf<FillNotice>()

    private fun recordApplies(failOrderNo: String? = null) {
        Mockito.doAnswer {
            val notice = it.getArgument<FillNotice>(0)
            if (notice.orderNo == failOrderNo) throw RuntimeException("boom")
            applied += Triple(notice, it.getArgument(1), it.getArgument(2))
            null
        }.`when`(holdingSync).applyFill(anyNonNull(), Mockito.anyLong(), anyNonNull(), anyNonNull())
        Mockito.doAnswer {
            accepted += it.getArgument<FillNotice>(0)
            null
        }.`when`(tradeService).onOrderNotice(anyNonNull())
    }

    @Test
    fun `커서 다음 행을 순서대로 보유 현황에 반영하고 커서를 옮긴다`() {
        cursorStore["fill"] = 10
        Mockito.`when`(fills.findTop100ByIdGreaterThanOrderByIdAsc(10)).thenReturn(listOf(row(11), row(12)))
        Mockito.`when`(fills.findTop100ByIdGreaterThanOrderByIdAsc(12)).thenReturn(emptyList())
        recordApplies()

        assertEquals(2, projector.project())

        assertEquals(listOf("O11", "O12"), applied.map { it.first.orderNo })
        assertEquals("02", applied.first().first.sellBuyCode)
        // 원장 줄 ID 와 게이트웨이가 받은 시각을 그대로 넘긴다(앱이 반영한 시각이 아니다).
        assertEquals(listOf(11L, 12L), applied.map { it.second })
        assertEquals(receivedAt, applied.first().third)
        assertEquals(12L, cursorStore["fill"])
    }

    @Test
    fun `접수 통보는 보유 현황이 아니라 매매 기록의 미체결 확인으로 보낸다`() {
        cursorStore["fill"] = 0
        Mockito.`when`(fills.findTop100ByIdGreaterThanOrderByIdAsc(0)).thenReturn(listOf(row(1, filledFlag = "1")))
        Mockito.`when`(fills.findTop100ByIdGreaterThanOrderByIdAsc(1)).thenReturn(emptyList())
        recordApplies()

        projector.project()

        assertEquals(listOf("O1"), accepted.map { it.orderNo })
        assertEquals(0, applied.size)
    }

    @Test
    fun `한 행 반영이 실패해도 건너뛰고 다음 행을 이어서 처리한다`() {
        cursorStore["fill"] = 0
        Mockito.`when`(fills.findTop100ByIdGreaterThanOrderByIdAsc(0)).thenReturn(listOf(row(1), row(2)))
        Mockito.`when`(fills.findTop100ByIdGreaterThanOrderByIdAsc(2)).thenReturn(emptyList())
        recordApplies(failOrderNo = "O1")

        assertEquals(2, projector.project())
        assertEquals(listOf("O2"), applied.map { it.first.orderNo })
        assertEquals(2L, cursorStore["fill"])
    }

    private fun <T> anyNonNull(): T = Mockito.any<T>()
}

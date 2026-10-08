package com.quantlog.gatewayclient

import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.Instant
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 원장(`kis_broker_fill`) 원문을 읽어 주문별 체결을 구하는 부분 ([KisBrokerFillOrderFills]). */
class KisBrokerFillOrderFillsTest {
    private val rows = mutableListOf<KisBrokerFillRow>()
    private var cursor = 0L
    private val repository =
        Mockito.mock(KisBrokerFillRowRepository::class.java).also { repo ->
            Mockito.`when`(repo.findTop100ByIdGreaterThanOrderByIdAsc(Mockito.anyLong())).thenAnswer { inv ->
                rows.filter { it.id!! > inv.getArgument<Long>(0) }.take(100)
            }
        }
    private val cursors =
        Mockito.mock(ProjectionCursorRepository::class.java).also { repo ->
            Mockito.`when`(repo.findById("kis_broker_fill")).thenAnswer { Optional.of(ProjectionCursor("kis_broker_fill", cursor)) }
        }
    private val fills = KisBrokerFillOrderFills(repository, cursors)

    private fun add(
        orderNo: String,
        quantity: Int,
        fill: Boolean = true,
    ) {
        val f = MutableList(23) { "" }
        f[2] = orderNo
        f[4] = "02"
        f[8] = "005930"
        f[9] = "$quantity"
        f[10] = "70100"
        f[12] = "0"
        f[13] = if (fill) "2" else "1"
        f[16] = "10"
        rows += KisBrokerFillRow(id = rows.size + 1L, receivedAt = Instant.EPOCH, trId = "H0STCNI9", body = f.joinToString("^"))
    }

    @Test
    fun `주문별 체결은 원문에서 읽고 반영된 줄까지만 센다`() {
        add("A", 0, fill = false)
        add("A", 3)
        add("B", 5)
        add("A", 4)
        cursor = 3

        assertEquals(listOf(3), fills.projected("A").map { it.quantity })
        assertEquals(listOf(3, 4), fills.upTo("A", Long.MAX_VALUE).map { it.quantity })
        assertEquals(listOf("A", "B"), fills.projectedAll().map { it.orderNo })
        assertTrue(fills.accepted("A"))
    }

    @Test
    fun `새로 쌓인 줄은 다음 조회 때 이어서 읽는다`() {
        add("A", 3)
        cursor = 1
        assertEquals(1, fills.projected("A").size)

        add("A", 4)
        cursor = 2

        assertEquals(listOf(3, 4), fills.projected("A").map { it.quantity })
    }
}

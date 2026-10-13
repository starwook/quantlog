package com.quantlog.futures

import com.fasterxml.jackson.databind.ObjectMapper
import com.quantlog.broker.KisReply
import com.quantlog.broker.KisRest
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OptionChainCollectorTest {
    private val nov = YearMonth.of(2026, 11)
    private val clock = Clock.fixed(Instant.parse("2026-10-13T07:00:00Z"), ZoneId.of("Asia/Seoul"))

    /** 코드 → (행사가, 미결제약정). 없는 코드는 한투처럼 빈 output1 을 준다. */
    private class FakeKis(private val listed: Map<String, Pair<String, Long>>, private val index: String) : KisRest {
        val asked = mutableListOf<String>()

        /** 이 횟수만큼은 한투 초당 한도 초과로 답한다. */
        var limitedReplies = 0

        override fun get(
            path: String,
            trId: String,
            params: Map<String, String>,
            trCont: String,
        ): KisReply {
            val code = params.getValue("FID_INPUT_ISCD")
            if (limitedReplies > 0) {
                limitedReplies--
                return KisReply(ObjectMapper().readTree("""{"rt_cd":"1","msg_cd":"EGW00201","msg1":"초당 거래건수를 초과하였습니다."}"""))
            }
            asked += code
            val output1 =
                listed[code]?.let { (strike, oi) ->
                    """{"hts_kor_isnm":"C $strike","acpr":"$strike","futs_prpr":"1.00","hts_otst_stpl_qty":"$oi","acml_vol":"7"}"""
                } ?: """{"futs_prpr":"0.00"}"""
            val json = """{"rt_cd":"0","output1":$output1,"output3":{"bstp_nmix_prpr":"$index"}}"""
            return KisReply(ObjectMapper().readTree(json))
        }

        override fun post(
            path: String,
            trId: String,
            body: Map<String, String>,
            requestId: String?,
        ): KisReply = error("주문은 부르지 않는다")
    }

    private fun collect(kis: FakeKis): List<OptionStrike> {
        val store = Mockito.mock(OptionSnapshotStore::class.java)
        var saved: List<OptionStrike> = emptyList()
        Mockito.doAnswer {
            @Suppress("UNCHECKED_CAST")
            saved = it.arguments[3] as List<OptionStrike>
            null
        }.`when`(store).replace(anyDate(), anyMonth(), anyDecimal(), anyList(), anyDateTime())
        OptionChainCollector(FuturesOptionsClient(kis), store, clock, pauseMillis = 0).collect(nov)
        return saved
    }

    private fun anyDateTime(): LocalDateTime = Mockito.any(LocalDateTime::class.java) ?: LocalDateTime.MIN

    private fun anyDate(): LocalDate = Mockito.any(LocalDate::class.java) ?: LocalDate.MIN

    private fun anyMonth(): YearMonth = Mockito.any(YearMonth::class.java) ?: nov

    private fun anyDecimal(): BigDecimal = Mockito.any(BigDecimal::class.java) ?: BigDecimal.ZERO

    private fun anyList(): List<OptionStrike> = Mockito.anyList<OptionStrike>() ?: emptyList()

    @Test
    fun `지수 위아래 범위의 행사가를 1000 아래는 정수부 코드, 위는 순번 코드로 모은다`() {
        // 지수 990 → 범위 910.8 ~ 1069.2
        val kis =
            FakeKis(
                mapOf(
                    "B01611A01" to ("1000.00" to 10L),
                    "C01611A01" to ("1000.00" to 20L),
                    "B01611A02" to ("1005.00" to 30L),
                    "B01611A03" to ("1100.00" to 99L),
                    "B01611997" to ("997.50" to 40L),
                    "C01611997" to ("997.50" to 50L),
                    "C01611912" to ("912.50" to 60L),
                    "B01611907" to ("907.50" to 99L),
                ),
                index = "990.00",
            )

        val saved = collect(kis).sortedBy { it.strike }

        assertEquals(listOf("912.50", "997.50", "1000.00", "1005.00"), saved.map { it.strike.toPlainString() })
        assertNull(saved[0].call)
        assertEquals(60, saved[0].putOi)
        assertEquals(40, saved[1].callOi)
        assertEquals(20, saved[2].putOi)
        assertNull(saved[3].put)
        // 범위 밖(907.5)은 묻지 않고, 1100 에서 멈춘다.
        assertEquals(false, kis.asked.contains("B01611907"))
        assertEquals(false, kis.asked.contains("B01611A04"))
    }

    @Test
    fun `지수가 낮아 범위가 1000 에 못 미치면 순번 코드는 지수 확인용 한 번만 부른다`() {
        val kis = FakeKis(mapOf("B01611A01" to ("1000.00" to 10L), "B01611900" to ("900.00" to 5L)), index = "900.00")

        val saved = collect(kis)

        assertEquals(listOf("900.00"), saved.map { it.strike.toPlainString() })
        assertEquals(1, kis.asked.count { it.startsWith("B01611A") })
    }

    @Test
    fun `한투 초당 한도 초과는 다시 불러 이어 간다`() {
        val kis = FakeKis(mapOf("B01611A01" to ("1000.00" to 10L), "B01611900" to ("900.00" to 5L)), index = "900.00")
        kis.limitedReplies = 2

        assertEquals(listOf("900.00"), collect(kis).map { it.strike.toPlainString() })
    }
}

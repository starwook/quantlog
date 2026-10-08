package com.quantlog.broker

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 한투 요청 형태(TR ID·파라미터)와 응답 해석을 검증한다. 게이트웨이 없이 가짜 [KisRest] 로 — 응답 필드는 2026-09-29 실측 원문 기준. */
class KisBrokerClientTest {
    private class Call(
        val method: String,
        val path: String,
        val trId: String,
        val params: Map<String, String>,
        val trCont: String,
        val requestId: String?,
    )

    private val mapper = jacksonObjectMapper()
    private val calls = mutableListOf<Call>()
    private val replies = ArrayDeque<KisReply>()
    private var now = Instant.parse("2026-10-08T01:00:00Z")
    private val clock =
        object : Clock() {
            override fun getZone() = ZoneOffset.UTC

            override fun withZone(zone: java.time.ZoneId?): Clock = this

            override fun instant(): Instant = now
        }
    private val latest = LatestPrices()
    private val rest =
        object : KisRest {
            override fun get(
                path: String,
                trId: String,
                params: Map<String, String>,
                trCont: String,
            ): KisReply {
                calls += Call("GET", path, trId, params, trCont, null)
                return replies.removeFirst()
            }

            override fun post(
                path: String,
                trId: String,
                body: Map<String, String>,
                requestId: String?,
            ): KisReply {
                calls += Call("POST", path, trId, body, "", requestId)
                return replies.removeFirst()
            }
        }
    private val client = KisBrokerClient(rest, latest, clock)

    private fun reply(
        json: String,
        trCont: String = "",
    ) = KisReply(mapper.readTree(json) as JsonNode, trCont)

    private fun ok(output: String) = reply("""{"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상처리","output":$output}""")

    @Test
    fun `국내 현재가 조회`() {
        replies += ok("""{"stck_prpr":"273000","aspr_unit":"500"}""")

        assertEquals(Quote(BigDecimal("273000"), BigDecimal("500")), client.quote(Market.KR, "005930"))
        assertEquals("FHKST01010100", calls.single().trId)
        assertEquals(mapOf("FID_COND_MRKT_DIV_CODE" to "J", "FID_INPUT_ISCD" to "005930"), calls.single().params)
    }

    @Test
    fun `실시간 최신가가 10초 안이고 호가 단위를 알면 REST 를 부르지 않고 그 값을 쓴다`() {
        replies += ok("""{"stck_prpr":"273000","aspr_unit":"500"}""")
        client.quote(Market.KR, "005930")
        latest.record("005930", BigDecimal("274000"), now.minusSeconds(3))

        assertEquals(Quote(BigDecimal("274000"), BigDecimal("500")), client.quote(Market.KR, "005930"))
        assertEquals(1, calls.size)
    }

    @Test
    fun `실시간 최신가가 오래됐으면 REST 로 받는다`() {
        replies += ok("""{"stck_prpr":"273000","aspr_unit":"500"}""")
        client.quote(Market.KR, "005930")
        latest.record("005930", BigDecimal("274000"), now.minusSeconds(30))
        replies += ok("""{"stck_prpr":"275000","aspr_unit":"500"}""")

        assertEquals(BigDecimal("275000"), client.quote(Market.KR, "005930").price)
        assertEquals(2, calls.size)
    }

    @Test
    fun `전일 종가는 하루 한 번만 받고 없으면 기준가를 쓴다`() {
        replies += ok("""{"stck_prdy_clpr":"0","stck_sdpr":"270000"}""")

        assertEquals(BigDecimal("270000"), client.previousClose(Market.KR, "005930"))
        assertEquals(BigDecimal("270000"), client.previousClose(Market.KR, "005930"))
        assertEquals(1, calls.size)
    }

    @Test
    fun `잔고 응답에서 수량이 있는 종목만 읽는다`() {
        replies +=
            reply(
                """{"rt_cd":"0","msg1":"ok","output1":[
                    {"pdno":"005930","prdt_name":"삼성전자","hldg_qty":"3","pchs_avg_pric":"70000.5000","prpr":"71000"},
                    {"pdno":"000660","prdt_name":"SK하이닉스","hldg_qty":"0","pchs_avg_pric":"0","prpr":"1"}
                ],"output2":[{}]}""",
            )

        val holding = client.holdings(Market.KR).single()

        assertEquals("005930", holding.symbol)
        assertEquals(0, BigDecimal("70000.5").compareTo(holding.averagePrice))
        assertEquals("VTTC8434R", calls.single().trId)
        assertNull(calls.single().params["CANO"]) // 계좌번호는 앱이 모른다 — 게이트웨이가 끼운다
    }

    @Test
    fun `매수 주문은 정수 가격과 KRX 로 나가고 요청 ID 를 붙인다`() {
        replies += ok("""{"ODNO":"0000000777","KRX_FWDG_ORD_ORGNO":"06010"}""")

        val receipt = client.placeOrder(OrderRequest(Market.KR, "005930", Side.BUY, 1, BigDecimal("70000.4")))

        assertEquals("0000000777", receipt.orderNo)
        assertEquals("06010", receipt.branchNo)
        val call = calls.single()
        assertEquals("VTTC0012U", call.trId)
        assertEquals("70000", call.params["ORD_UNPR"])
        assertEquals("KRX", call.params["EXCG_ID_DVSN_CD"])
        assertNotNull(call.requestId)
    }

    @Test
    fun `매도 주문은 VTTC0011U 와 SLL_TYPE 01 로 나가고 호출마다 새 요청 ID 를 쓴다`() {
        replies += ok("""{"ODNO":"1"}""")
        replies += ok("""{"ODNO":"2"}""")
        val sell = OrderRequest(Market.KR, "005930", Side.SELL, 1, BigDecimal("70000"))

        client.placeOrder(sell)
        client.placeOrder(sell)

        assertEquals("VTTC0011U", calls[0].trId)
        assertEquals("01", calls[0].params["SLL_TYPE"])
        assertTrue(calls[0].requestId != calls[1].requestId)
    }

    @Test
    fun `취소는 VTTC0013U 로 원주문번호와 조직번호를 보낸다`() {
        replies += ok("""{}""")

        client.cancelOrder(CancelRequest(Market.KR, "005930", "0001", "06010", 2, BigDecimal("70000")))

        val call = calls.single()
        assertEquals("VTTC0013U", call.trId)
        assertEquals("0001", call.params["ORGN_ODNO"])
        assertEquals("06010", call.params["KRX_FWDG_ORD_ORGNO"])
        assertEquals("02", call.params["RVSE_CNCL_DVSN_CD"])
        assertNotNull(call.requestId)
    }

    @Test
    fun `체결가는 체결조회의 실측 필드명으로 읽는다`() {
        // 2026-09-29 삼성전자 실측: 지정가 273,000원 매수가 실제로는 272,000원에 체결됨
        replies +=
            reply(
                """{"rt_cd":"0","msg1":"ok","output1":[{"odno":"0000008307","tot_ccld_qty":"1","avg_prvs":"272000"}],"output2":{}}""",
            )

        assertEquals(0, BigDecimal("272000").compareTo(client.filledPrice(Market.KR, "0000008307")))
        assertEquals("VTTC0081R", calls.single().trId)
        assertEquals("0000008307", calls.single().params["ODNO"])
        assertEquals("20261008", calls.single().params["INQR_STRT_DT"])
    }

    @Test
    fun `행이 없으면 Unknown, 일부만 체결됐으면 Open`() {
        replies += reply("""{"rt_cd":"0","msg1":"ok","output1":[],"output2":{}}""")
        assertEquals(OrderStatus.Unknown, client.orderStatus(Market.KR, "9", 1))

        replies +=
            reply(
                """{"rt_cd":"0","msg1":"ok","output1":[{"odno":"9","tot_ccld_qty":"1","avg_prvs":"100"}],"output2":{}}""",
            )
        assertEquals(OrderStatus.Open, client.orderStatus(Market.KR, "9", 2))
    }

    @Test
    fun `오늘 체결 전체는 연속조회 표시가 있으면 다음 쪽을 이어 받는다`() {
        replies +=
            reply(
                """{"rt_cd":"0","msg1":"ok","ctx_area_fk100":"FK1","ctx_area_nk100":"NK1",
                    "output1":[{"odno":"1","tot_ccld_qty":"2","avg_prvs":"100"}]}""",
                trCont = "M",
            )
        replies +=
            reply(
                """{"rt_cd":"0","msg1":"ok","output1":[
                    {"odno":"2","tot_ccld_qty":"0","avg_prvs":"0"},{"odno":"3","tot_ccld_qty":"1","avg_prvs":"50"}]}""",
                trCont = "D",
            )

        val fills = client.todayOrderFills(Market.KR)

        assertEquals(listOf("1", "3"), fills.map { it.orderNo })
        assertEquals(2, calls.size)
        assertEquals("", calls[0].trCont)
        assertEquals("N", calls[1].trCont)
        assertEquals("FK1", calls[1].params["CTX_AREA_FK100"])
        assertEquals("NK1", calls[1].params["CTX_AREA_NK100"])
    }

    @Test
    fun `rt_cd 가 0 이 아니면 msg_cd 를 담은 KisApiException`() {
        replies += reply("""{"rt_cd":"1","msg_cd":"EGW00123","msg1":"종목 없음"}""")

        val e = assertFailsWith<KisApiException> { client.quote(Market.KR, "999999") }

        assertEquals("EGW00123", e.code)
        assertTrue(e.message!!.contains("종목 없음"))
    }

    @Test
    fun `분봉 응답은 실측 필드명을 그대로 매핑한다`() {
        // 2026-09-29 삼성전자 실측 원문 (docs/kis-api/README.md)
        val response =
            mapper.readTree(
                """{"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상처리 되었습니다.","output1":{},"output2":[
                    {"stck_bsop_date":"20260929","stck_cntg_hour":"100000","stck_prpr":"272000","stck_oprc":"271500","stck_hgpr":"272000","stck_lwpr":"271000","cntg_vol":"46062"},
                    {"stck_bsop_date":"20260929","stck_cntg_hour":"095900","stck_prpr":"271500","stck_oprc":"271000","stck_hgpr":"271500","stck_lwpr":"271000","cntg_vol":"11418"}
                ]}""",
            )

        val candles = KisMinuteChartParser.parse(response)

        assertEquals(2, candles.size)
        assertEquals(
            MinuteCandle(
                LocalDate.of(2026, 9, 29),
                LocalTime.of(10, 0, 0),
                BigDecimal("271500"),
                BigDecimal("272000"),
                BigDecimal("271000"),
                BigDecimal("272000"),
                46062,
            ),
            candles[0],
        )
    }
}

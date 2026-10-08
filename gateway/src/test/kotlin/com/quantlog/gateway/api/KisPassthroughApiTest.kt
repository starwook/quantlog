package com.quantlog.gateway.api

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.quantlog.gateway.broker.CallPriority
import com.quantlog.gateway.kis.KisApiClient
import com.quantlog.gateway.kis.KisProperties
import com.quantlog.gateway.record.KisOrder
import com.quantlog.gateway.record.KisOrderRepository
import com.quantlog.gateway.record.KisOrderService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/** 앱↔게이트웨이 한투 REST 통로(docs/contracts/README.md)의 게이트웨이 쪽: 한투 요청·응답을 그대로 전하고, 계좌번호만 끼우고, 주문은 요청 ID 로 한 번만 보낸다. */
class KisPassthroughApiTest {
    private class Sent(
        val path: String,
        val trId: String,
        val method: String,
        val query: Map<String, String>,
        val body: String?,
        val trCont: String?,
        val urgent: Boolean,
    )

    private val sent = mutableListOf<Sent>()
    private var reply = KisApiClient.RawResponse(200, """{"rt_cd":"0","msg1":"ok","output":{"a":"1"}}""", null)
    private val api =
        Mockito.mock(KisApiClient::class.java).also { api ->
            Mockito.`when`(
                api.raw(Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyMap(), Mockito.any(), Mockito.any()),
            ).thenAnswer {
                sent +=
                    Sent(
                        it.getArgument(0), it.getArgument(1), it.getArgument(2), it.getArgument<Map<String, String>>(3),
                        it.getArgument(4), it.getArgument(5), CallPriority.isUrgent(),
                    )
                reply
            }
        }
    private val rows = mutableMapOf<String, KisOrder>()
    private val orderRepository =
        Mockito.mock(KisOrderRepository::class.java).also { repo ->
            Mockito.`when`(repo.findByRequestId(Mockito.anyString())).thenAnswer { rows[it.getArgument<String>(0)] }
            Mockito.`when`(repo.save(Mockito.any(KisOrder::class.java))).thenAnswer {
                it.getArgument<KisOrder>(0).also {
                        row ->
                    rows[row.requestId] = row
                }
            }
        }
    private val mapper = jacksonObjectMapper()
    private val mvc: MockMvc =
        MockMvcBuilders.standaloneSetup(
            KisPassthroughApi(api, KisProperties(account = "12345678-01"), KisOrderService(orderRepository), mapper),
        ).setControllerAdvice(ApiExceptionHandler()).build()

    @Test
    fun `GET 은 한투 응답을 본문·상태·tr_cont 까지 그대로 돌려준다`() {
        reply = KisApiClient.RawResponse(200, """{"rt_cd":"0","output":{"x":"y"},"ctx_area_nk100":"NK"}""", "M")

        mvc.perform(
            get("/api/kis/uapi/domestic-stock/v1/quotations/inquire-price?FID_INPUT_ISCD=005930&CTX=").header("tr_id", "FHKST01010100"),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("tr_cont", "M"))
            .andExpect(content().string("""{"rt_cd":"0","output":{"x":"y"},"ctx_area_nk100":"NK"}"""))

        val s = sent.single()
        assertEquals("/uapi/domestic-stock/v1/quotations/inquire-price", s.path)
        assertEquals("FHKST01010100", s.trId)
        assertEquals(mapOf("FID_INPUT_ISCD" to "005930", "CTX" to ""), s.query) // 계좌 TR 이 아니라 계좌번호를 끼우지 않는다
    }

    @Test
    fun `한투가 오류로 답해도 상태와 본문을 바꾸지 않는다`() {
        reply = KisApiClient.RawResponse(500, """{"rt_cd":"1","msg_cd":"EGW00201","msg1":"초당 거래건수를 초과"}""", null)

        mvc.perform(get("/api/kis/uapi/x").header("tr_id", "T"))
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.msg_cd").value("EGW00201"))
    }

    @Test
    fun `계좌 TR 의 GET 에는 계좌번호를 끼워 넣고 tr_cont 를 그대로 전한다`() {
        mvc.perform(
            get(
                "/api/kis/uapi/domestic-stock/v1/trading/inquire-balance?AFHR_FLPR_YN=N",
            ).header("tr_id", "VTTC8434R").header("tr_cont", "N"),
        )
            .andExpect(status().isOk)

        val s = sent.single()
        assertEquals("12345678", s.query["CANO"])
        assertEquals("01", s.query["ACNT_PRDT_CD"])
        assertEquals("N", s.query["AFHR_FLPR_YN"])
        assertEquals("N", s.trCont)
    }

    @Test
    fun `계좌 TR 의 POST 본문에도 계좌번호를 끼우고 앱이 보낸 값은 덮어쓴다`() {
        mvc.perform(
            post("/api/kis/uapi/domestic-stock/v1/trading/order-cash").header("tr_id", "VTTC0012U")
                .contentType(MediaType.APPLICATION_JSON).content("""{"PDNO":"005930","CANO":"HACKED"}"""),
        ).andExpect(status().isOk)

        val body = mapper.readTree(sent.single().body)
        assertEquals("005930", body.path("PDNO").asText())
        assertEquals("12345678", body.path("CANO").asText())
        assertEquals("01", body.path("ACNT_PRDT_CD").asText())
    }

    @Test
    fun `X-Call-Priority urgent 면 즉발 등급으로 한투에 나간다`() {
        mvc.perform(get("/api/kis/uapi/x").header("tr_id", "T").header("X-Call-Priority", "urgent")).andExpect(status().isOk)
        mvc.perform(get("/api/kis/uapi/x").header("tr_id", "T")).andExpect(status().isOk)

        assertTrue(sent[0].urgent)
        assertEquals(false, sent[1].urgent)
    }

    @Test
    fun `한투 경로가 아니거나 tr_id 가 없으면 422`() {
        mvc.perform(get("/api/kis/oauth2/tokenP").header("tr_id", "T")).andExpect(status().isUnprocessableEntity)
        mvc.perform(get("/api/kis/uapi/x")).andExpect(status().isUnprocessableEntity)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `요청 ID 가 있는 주문은 한 번만 나가고 같은 ID 의 재요청은 저장된 한투 응답을 돌려준다`() {
        reply = KisApiClient.RawResponse(200, """{"rt_cd":"0","output":{"ODNO":"0001"}}""", null)

        fun order() =
            post("/api/kis/uapi/domestic-stock/v1/trading/order-cash").header("tr_id", "VTTC0012U").header("X-Request-Id", "req-1")
                .contentType(MediaType.APPLICATION_JSON).content("""{"PDNO":"005930"}""")

        mvc.perform(order()).andExpect(status().isOk).andExpect(jsonPath("$.output.ODNO").value("0001"))
        mvc.perform(order()).andExpect(status().isOk).andExpect(jsonPath("$.output.ODNO").value("0001"))

        assertEquals(1, sent.size)
        val row = rows.getValue("req-1")
        assertEquals(KisOrder.DONE, row.status)
        assertEquals("""{"PDNO":"005930"}""", row.requestBody) // 계좌번호를 끼우기 전 앱이 보낸 그대로
    }

    @Test
    fun `요청 ID 가 없으면 기록하지 않는다`() {
        mvc.perform(get("/api/kis/uapi/x").header("tr_id", "T")).andExpect(status().isOk)

        assertTrue(rows.isEmpty())
        assertNull(sent.single().body)
    }
}

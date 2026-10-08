package com.quantlog.gatewayclient

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.quantlog.broker.CallPriority
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.InetSocketAddress

/** 앱↔게이트웨이 한투 REST 통로(docs/contracts/README.md)의 앱 쪽: 가짜 게이트웨이 서버에 진짜 HTTP 로 부른다. */
class GatewayClientTest {
    private class Seen(val method: String, val path: String, val headers: Map<String, String>, val body: String)

    private val seen = mutableListOf<Seen>()
    private var status = 200
    private var response = "{}"
    private val server: HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex: HttpExchange ->
                seen +=
                    Seen(
                        ex.requestMethod, ex.requestURI.toString(),
                        ex.requestHeaders.mapValues {
                            it.value.first()
                        },
                        ex.requestBody.readBytes().decodeToString(),
                    )
                val bytes = response.toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(status, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            start()
        }
    private val mapper: ObjectMapper = jacksonObjectMapper().findAndRegisterModules()

    private fun client(token: String = "secret") =
        GatewayClient(
            GatewayClientProperties(baseUrl = "http://127.0.0.1:${server.address.port}", token = token, readTimeoutMillis = 3000),
            mapper,
        )

    @AfterEach
    fun stop() = server.stop(0)

    private val kisOk = """{"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상처리","output":{"stck_prpr":"70000"}}"""

    @Test
    fun `GET 은 한투 경로와 파라미터를 그대로 보내고 tr_id·토큰 헤더를 붙이며 즉발이 아니면 우선순위 헤더를 안 붙인다`() {
        response = kisOk
        val reply =
            client().get(
                "/uapi/domestic-stock/v1/quotations/inquire-price",
                "FHKST01010100",
                mapOf("FID_INPUT_ISCD" to "005930", "CTX" to ""),
            )

        assertEquals("70000", reply.body.path("output").path("stck_prpr").asText())
        val call = seen.single()
        assertEquals("/api/kis/uapi/domestic-stock/v1/quotations/inquire-price?FID_INPUT_ISCD=005930&CTX=", call.path)
        assertEquals("FHKST01010100", call.headers["Tr_id"])
        assertEquals("secret", call.headers["X-gateway-token"])
        assertNull(call.headers["X-call-priority"])
        assertNull(call.headers["Tr_cont"])
    }

    @Test
    fun `즉발 등급 스레드의 호출은 X-Call-Priority urgent 를 붙인다`() {
        response = kisOk
        CallPriority.urgent { client().get("/uapi/x", "T", emptyMap()) }

        assertEquals("urgent", seen.single().headers["X-call-priority"])
    }

    @Test
    fun `POST 는 본문과 요청 ID 헤더를 보내고 연속조회 응답 헤더 tr_cont 를 돌려준다`() {
        response = kisOk
        client().post(
            "/uapi/domestic-stock/v1/trading/order-cash",
            "VTTC0012U",
            mapOf("PDNO" to "005930", "ORD_QTY" to "2"),
            requestId = "req-1",
        )

        val call = seen.single()
        assertEquals("POST", call.method)
        assertEquals("req-1", call.headers["X-request-id"])
        assertEquals("VTTC0012U", call.headers["Tr_id"])
        assertEquals("005930", mapper.readTree(call.body).path("PDNO").asText())
    }

    @Test
    fun `한투가 rt_cd 오류로 답해도 본문을 그대로 돌려준다 - 해석은 호출한 쪽이 한다`() {
        status = 500
        response = """{"rt_cd":"1","msg_cd":"EGW00201","msg1":"초당 거래건수를 초과"}"""

        val reply = client().get("/uapi/x", "T", emptyMap())

        assertEquals("EGW00201", reply.body.path("msg_cd").asText())
    }

    @Test
    fun `게이트웨이 자체 오류 응답은 상태와 코드를 담은 예외로 올라온다`() {
        status = 409
        response = """{"error":"결과를 모른다","code":"RESULT_UNKNOWN"}"""
        val e = assertThrows<GatewayException> { client().post("/uapi/x", "T", emptyMap(), requestId = "r") }

        assertEquals(409, e.status)
        assertEquals("RESULT_UNKNOWN", e.code)
        assertTrue(e.message!!.contains("결과를"))
    }

    @Test
    fun `게이트웨이에 닿지 못하면 닿지 못했다는 예외다`() {
        server.stop(0)
        assertThrows<GatewayUnavailableException> { client().get("/uapi/x", "T", emptyMap()) }
    }
}

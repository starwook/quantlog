package com.quantlog.gatewayclient

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.quantlog.broker.CallPriority
import com.quantlog.broker.Market
import com.quantlog.broker.OrderRequest
import com.quantlog.broker.OrderStatus
import com.quantlog.broker.Side
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.net.InetSocketAddress

/** 앱↔게이트웨이 HTTP 계약(docs/contracts/gateway-http.md)의 앱 쪽: 가짜 게이트웨이 서버에 진짜 HTTP 로 부른다. */
class GatewayBrokerClientTest {
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
        GatewayBrokerClient(
            GatewayClientProperties(baseUrl = "http://127.0.0.1:${server.address.port}", token = token, readTimeoutMillis = 3000),
            mapper,
        )

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun `시세 조회는 토큰 헤더를 붙이고 즉발이 아니면 우선순위 헤더를 안 붙인다`() {
        response = """{"price":70000,"tickSize":100}"""
        val quote = client().quote(Market.KR, "005930")

        assertEquals(0, BigDecimal("70000").compareTo(quote.price))
        assertEquals("/api/quotes/KR/005930", seen.single().path)
        assertEquals("secret", seen.single().headers["X-gateway-token"])
        assertNull(seen.single().headers["X-call-priority"])
    }

    @Test
    fun `즉발 등급 스레드의 호출은 X-Call-Priority urgent 를 붙인다`() {
        response = """{"price":70000,"tickSize":100}"""
        CallPriority.urgent { client().quote(Market.KR, "005930") }

        assertEquals("urgent", seen.single().headers["X-call-priority"])
    }

    @Test
    fun `주문은 호출마다 새 요청 ID 를 붙여 보내고 접수 결과를 돌려준다`() {
        response = """{"orderNo":"0001","message":"정상","branchNo":"06010"}"""
        val order = OrderRequest(Market.KR, "005930", Side.BUY, 2, BigDecimal("70100"))
        val receipt = client().placeOrder(order)
        client().placeOrder(order)

        assertEquals("0001", receipt.orderNo)
        assertEquals("06010", receipt.branchNo)
        val ids = seen.map { mapper.readTree(it.body).path("requestId").asText() }
        assertEquals(2, ids.toSet().size)
        val body = mapper.readTree(seen.first().body)
        assertEquals("BUY", body.path("side").asText())
        assertEquals(2, body.path("quantity").asInt())
    }

    @Test
    fun `게이트웨이 오류 응답은 상태와 증권사 코드를 담은 예외로 올라온다`() {
        status = 502
        response = """{"error":"초당 거래건수를 초과","code":"EGW00201"}"""
        val e = assertThrows<GatewayException> { client().quote(Market.KR, "005930") }

        assertEquals(502, e.status)
        assertEquals("EGW00201", e.code)
        assertTrue(e.message!!.contains("초당"))
    }

    @Test
    fun `주문 상태 응답을 FILLED, OPEN, UNKNOWN 으로 옮긴다`() {
        response = """{"type":"FILLED","price":70050}"""
        val filled = client().orderStatus(Market.KR, "0001", 2)
        assertTrue(filled is OrderStatus.Filled && filled.price.compareTo(BigDecimal("70050")) == 0)

        response = """{"type":"OPEN"}"""
        assertEquals(OrderStatus.Open, client().orderStatus(Market.KR, "0001", 2))
        response = """{"type":"UNKNOWN"}"""
        assertEquals(OrderStatus.Unknown, client().orderStatus(Market.KR, "0001", 2))
    }

    @Test
    fun `게이트웨이에 닿지 못하면 닿지 못했다는 예외다`() {
        server.stop(0)
        assertThrows<GatewayUnavailableException> { client().quote(Market.KR, "005930") }
    }

    @Test
    fun `체결가 응답이 null 이면 null 이다`() {
        response = """{"price":null}"""
        assertNull(client().filledPrice(Market.KR, "0001"))
    }
}

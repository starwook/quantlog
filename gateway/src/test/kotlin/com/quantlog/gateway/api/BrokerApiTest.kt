package com.quantlog.gateway.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.quantlog.gateway.broker.BrokerClient
import com.quantlog.gateway.broker.BuyingPower
import com.quantlog.gateway.broker.CallPriority
import com.quantlog.gateway.broker.Market
import com.quantlog.gateway.broker.OrderReceipt
import com.quantlog.gateway.broker.OrderRequest
import com.quantlog.gateway.broker.OrderStatus
import com.quantlog.gateway.broker.Quote
import com.quantlog.gateway.kis.KisApiException
import com.quantlog.gateway.record.BrokerOrder
import com.quantlog.gateway.record.BrokerOrderRepository
import com.quantlog.gateway.record.OrderService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.math.BigDecimal

/** 앱↔게이트웨이 HTTP 계약(docs/contracts/gateway-http.md)의 게이트웨이 쪽: 경로·필드 이름·오류 코드. 앱 쪽은 GatewayBrokerClientTest 가 같은 형식을 쓴다. */
class BrokerApiTest {
    private val rows = mutableMapOf<String, BrokerOrder>()
    private val repository =
        Mockito.mock(BrokerOrderRepository::class.java).also { repo ->
            Mockito.`when`(repo.findByRequestId(Mockito.anyString())).thenAnswer { rows[it.getArgument<String>(0)] }
            Mockito.`when`(repo.save(Mockito.any(BrokerOrder::class.java))).thenAnswer {
                it.getArgument<BrokerOrder>(0).also { row -> rows[row.requestId] = row }
            }
        }

    private class FakeBroker : BrokerClient {
        var urgentSeen: Boolean? = null
        var placeFailure: Exception? = null
        var status: OrderStatus = OrderStatus.Open

        override fun buyingPower(
            market: Market,
            symbol: String,
            price: BigDecimal,
        ) = BuyingPower("KRW", BigDecimal.TEN, BigDecimal.ONE)

        override fun minuteCandles(
            market: Market,
            symbol: String,
            atTime: java.time.LocalTime,
        ) = emptyList<com.quantlog.gateway.broker.MinuteCandle>()

        override fun filledPrice(
            market: Market,
            orderNo: String,
        ): BigDecimal? = null

        override fun holdings(market: Market) = emptyList<com.quantlog.gateway.broker.Holding>()

        override fun quote(
            market: Market,
            symbol: String,
        ): Quote {
            urgentSeen = CallPriority.isUrgent()
            return Quote(BigDecimal("70000"), BigDecimal("100"))
        }

        override fun placeOrder(order: OrderRequest): OrderReceipt {
            placeFailure?.let { throw it }
            return OrderReceipt("0001", "정상", "06010")
        }

        override fun orderStatus(
            market: Market,
            orderNo: String,
            quantity: Int,
        ) = status
    }

    private val broker = FakeBroker()
    private val mapper: ObjectMapper =
        jacksonObjectMapper().findAndRegisterModules().disable(
            SerializationFeature.WRITE_DATES_AS_TIMESTAMPS,
        )
    private val mvc: MockMvc =
        MockMvcBuilders.standaloneSetup(BrokerApi(broker, OrderService(broker, repository)))
            .setControllerAdvice(ApiExceptionHandler())
            .setMessageConverters(MappingJackson2HttpMessageConverter(mapper))
            .build()

    private fun orderJson(requestId: String) =
        """{"requestId":"$requestId","market":"KR","symbol":"005930","side":"BUY","quantity":2,"limitPrice":70100}"""

    @Test
    fun `주문은 접수번호와 조직번호를 돌려주고 같은 요청 ID 재전송은 같은 결과다`() {
        repeat(2) {
            mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(orderJson("r1")))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.orderNo").value("0001"))
                .andExpect(jsonPath("$.branchNo").value("06010"))
        }
    }

    @Test
    fun `증권사 거절은 502 와 msg_cd 로, 결과를 모르는 재요청은 409 RESULT_UNKNOWN 으로 돌려준다`() {
        broker.placeFailure = KisApiException("초당 거래건수를 초과", code = "EGW00201")
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(orderJson("r2")))
            .andExpect(status().isBadGateway)
            .andExpect(jsonPath("$.code").value("EGW00201"))

        rows["r3"] = BrokerOrder("r3", "PLACE", "KR", "005930", "BUY", 2, BigDecimal("70100"), null, BrokerOrder.SENDING)
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(orderJson("r3")))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("RESULT_UNKNOWN"))
    }

    @Test
    fun `X-Call-Priority urgent 헤더가 있을 때만 즉발 등급으로 증권사를 부른다`() {
        mvc.perform(get("/api/quotes/KR/005930")).andExpect(status().isOk).andExpect(jsonPath("$.price").value(70000))
        assertEquals(false, broker.urgentSeen)

        mvc.perform(get("/api/quotes/KR/005930").header("X-Call-Priority", "urgent")).andExpect(status().isOk)
        assertTrue(broker.urgentSeen == true)
    }

    @Test
    fun `주문 상태는 type 과 체결가로 전한다`() {
        broker.status = OrderStatus.Filled(BigDecimal("70050"))
        mvc.perform(get("/api/orders/0001/status?market=KR&quantity=2"))
            .andExpect(jsonPath("$.type").value("FILLED"))
            .andExpect(jsonPath("$.price").value(70050))

        broker.status = OrderStatus.Open
        mvc.perform(get("/api/orders/0001/status?market=KR&quantity=2")).andExpect(jsonPath("$.type").value("OPEN"))
    }
}

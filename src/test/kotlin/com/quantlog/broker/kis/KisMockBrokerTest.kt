package com.quantlog.broker.kis

import com.fasterxml.jackson.databind.ObjectMapper
import com.quantlog.broker.Market
import com.quantlog.broker.OrderRequest
import com.quantlog.broker.Side
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** KIS 서버 없이 요청 형태(TR ID·헤더·바디)와 응답 파싱을 검증한다. 실제 응답 필드는 첫 실연동에서 재확인한다. */
class KisMockBrokerTest {
    private val base = "https://kis.test"
    private lateinit var server: MockRestServiceServer
    private lateinit var broker: KisMockBroker

    @BeforeEach
    fun setUp() {
        val properties =
            KisProperties(baseUrl = base, appKey = "key", appSecret = "secret", account = "12345678-01", minIntervalMillis = 0)
        val builder = RestClient.builder()
        server = MockRestServiceServer.bindTo(builder).build()
        val api = KisApiClient(properties, KisTokenProvider(properties, builder), ObjectMapper(), builder)
        broker = KisMockBroker(api, properties)

        server.expect(requestTo("$base/oauth2/tokenP"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.grant_type").value("client_credentials"))
            .andExpect(jsonPath("$.appkey").value("key"))
            .andRespond(withSuccess("""{"access_token":"tok","expires_in":86400}""", MediaType.APPLICATION_JSON))
    }

    private fun ok(output: String) =
        withSuccess(
            """{"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상처리","output":$output}""",
            MediaType.APPLICATION_JSON,
        )

    @Test
    fun `미국 현재가 조회`() {
        server.expect(requestTo("$base/uapi/overseas-price/v1/quotations/price?AUTH=&EXCD=NAS&SYMB=AAPL"))
            .andExpect(header("tr_id", "HHDFS00000300"))
            .andExpect(header("authorization", "Bearer tok"))
            .andExpect(header("appkey", "key"))
            .andRespond(ok("""{"last":"187.50"}"""))

        assertEquals(BigDecimal("187.50"), broker.currentPrice(Market.NASDAQ, "AAPL"))
        server.verify()
    }

    @Test
    fun `국내 현재가 조회`() {
        server.expect(requestTo("$base/uapi/domestic-stock/v1/quotations/inquire-price?FID_COND_MRKT_DIV_CODE=J&FID_INPUT_ISCD=005930"))
            .andExpect(header("tr_id", "FHKST01010100"))
            .andRespond(ok("""{"stck_prpr":"70000"}"""))

        assertEquals(BigDecimal("70000"), broker.currentPrice(Market.KR, "005930"))
    }

    @Test
    fun `미국 매수 주문은 모의 TR ID 와 지정가 바디로 나간다`() {
        server.expect(requestTo("$base/uapi/overseas-stock/v1/trading/order"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("tr_id", "VTTT1002U"))
            .andExpect(jsonPath("$.CANO").value("12345678"))
            .andExpect(jsonPath("$.ACNT_PRDT_CD").value("01"))
            .andExpect(jsonPath("$.OVRS_EXCG_CD").value("NASD"))
            .andExpect(jsonPath("$.PDNO").value("AAPL"))
            .andExpect(jsonPath("$.ORD_QTY").value("1"))
            .andExpect(jsonPath("$.OVRS_ORD_UNPR").value("188.44"))
            .andExpect(jsonPath("$.ORD_DVSN").value("00"))
            .andExpect(jsonPath("$.SLL_TYPE").value(""))
            .andRespond(ok("""{"ODNO":"0000123456","ORD_TMD":"223001"}"""))

        val receipt = broker.placeOrder(OrderRequest(Market.NASDAQ, "AAPL", Side.BUY, 1, BigDecimal("188.4375")))
        assertEquals("0000123456", receipt.orderNo)
    }

    @Test
    fun `미국 매도 주문 TR ID`() {
        server.expect(requestTo("$base/uapi/overseas-stock/v1/trading/order"))
            .andExpect(header("tr_id", "VTTT1001U"))
            .andExpect(jsonPath("$.SLL_TYPE").value("00"))
            .andExpect(jsonPath("$.OVRS_EXCG_CD").value("NYSE"))
            .andRespond(ok("""{"ODNO":"1"}"""))

        broker.placeOrder(OrderRequest(Market.NYSE, "KO", Side.SELL, 1, BigDecimal("60")))
    }

    @Test
    fun `국내 매수 주문은 정수 가격과 KRX 로 나간다`() {
        server.expect(requestTo("$base/uapi/domestic-stock/v1/trading/order-cash"))
            .andExpect(header("tr_id", "VTTC0012U"))
            .andExpect(jsonPath("$.PDNO").value("005930"))
            .andExpect(jsonPath("$.ORD_UNPR").value("70000"))
            .andExpect(jsonPath("$.EXCG_ID_DVSN_CD").value("KRX"))
            .andRespond(ok("""{"ODNO":"0000000777"}"""))

        assertEquals("0000000777", broker.placeOrder(OrderRequest(Market.KR, "005930", Side.BUY, 1, BigDecimal("70000"))).orderNo)
    }

    @Test
    fun `미국 잔고는 수량 0 종목을 제외하고 거래소를 매핑한다`() {
        val query = "CANO=12345678&ACNT_PRDT_CD=01&OVRS_EXCG_CD=NASD&TR_CRCY_CD=USD&CTX_AREA_FK200=&CTX_AREA_NK200="
        server.expect(requestTo("$base/uapi/overseas-stock/v1/trading/inquire-balance?$query"))
            .andExpect(header("tr_id", "VTTS3012R"))
            .andRespond(
                withSuccess(
                    """{"rt_cd":"0","msg1":"ok","output1":[
                        {"ovrs_excg_cd":"NASD","ovrs_pdno":"AAPL","ovrs_item_name":"APPLE","ovrs_cblc_qty":"2","pchs_avg_pric":"180.10","now_pric2":"187.50"},
                        {"ovrs_excg_cd":"NYSE","ovrs_pdno":"KO","ovrs_item_name":"COCA","ovrs_cblc_qty":"0","pchs_avg_pric":"0","now_pric2":"60"}
                    ],"output2":{}}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        val holdings = broker.holdings(Market.NASDAQ)
        assertEquals(1, holdings.size)
        assertEquals("AAPL", holdings[0].symbol)
        assertEquals(Market.NASDAQ, holdings[0].market)
        assertEquals(BigDecimal("180.10"), holdings[0].averagePrice)
    }

    @Test
    fun `rt_cd 가 0 이 아니면 KisApiException`() {
        server.expect(requestTo("$base/uapi/overseas-price/v1/quotations/price?AUTH=&EXCD=NAS&SYMB=ZZZZ"))
            .andRespond(withSuccess("""{"rt_cd":"1","msg_cd":"EGW00123","msg1":"종목 없음"}""", MediaType.APPLICATION_JSON))

        val e = assertFailsWith<KisApiException> { broker.currentPrice(Market.NASDAQ, "ZZZZ") }
        assert(e.message!!.contains("종목 없음"))
    }
}

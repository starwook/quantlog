package com.quantlog.broker.kis

import com.fasterxml.jackson.databind.ObjectMapper
import com.quantlog.broker.Market
import com.quantlog.broker.MinuteCandle
import com.quantlog.broker.OrderRequest
import com.quantlog.broker.Quote
import com.quantlog.broker.Side
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
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
import java.time.LocalDate
import java.time.LocalTime
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
        // 실시간 시세는 꺼져 있는 상태를 가정 — quote() 는 항상 REST로 폴백한다(기존 테스트 그대로).
        broker = KisMockBroker(api, properties, Mockito.mock(KisRealtimeClient::class.java))

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

        assertEquals(Quote(BigDecimal("187.50"), BigDecimal("0.01")), broker.quote(Market.NASDAQ, "AAPL"))
        server.verify()
    }

    @Test
    fun `국내 현재가 조회`() {
        server.expect(requestTo("$base/uapi/domestic-stock/v1/quotations/inquire-price?FID_COND_MRKT_DIV_CODE=J&FID_INPUT_ISCD=005930"))
            .andExpect(header("tr_id", "FHKST01010100"))
            .andRespond(ok("""{"stck_prpr":"273000","aspr_unit":"500"}"""))

        assertEquals(Quote(BigDecimal("273000"), BigDecimal("500")), broker.quote(Market.KR, "005930"))
    }

    @Test
    fun `국내 분봉 조회는 실측 필드명을 그대로 매핑한다`() {
        // 2026-09-29 삼성전자 실측 원문 (docs/kis-api/README.md)
        server.expect(
            requestTo(
                "$base/uapi/domestic-stock/v1/quotations/inquire-time-itemchartprice" +
                    "?FID_COND_MRKT_DIV_CODE=J&FID_INPUT_ISCD=005930&FID_INPUT_HOUR_1=100000&FID_PW_DATA_INCU_YN=Y&FID_ETC_CLS_CODE=",
            ),
        ).andExpect(header("tr_id", "FHKST03010200"))
            .andRespond(
                withSuccess(
                    """{"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상처리 되었습니다.","output1":{},"output2":[
                        {"stck_bsop_date":"20260929","stck_cntg_hour":"100000","stck_prpr":"272000","stck_oprc":"271500","stck_hgpr":"272000","stck_lwpr":"271000","cntg_vol":"46062"},
                        {"stck_bsop_date":"20260929","stck_cntg_hour":"095900","stck_prpr":"271500","stck_oprc":"271000","stck_hgpr":"271500","stck_lwpr":"271000","cntg_vol":"11418"}
                    ]}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        val candles = broker.minuteCandles(Market.KR, "005930", LocalTime.of(10, 0, 0))

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

    @Test
    fun `해외 분봉 조회는 HHDFS76950200 을 부르고 추정 필드명으로 매핑한다`() {
        // 2026-09-29: 공식 예제에 필드명이 안 적혀 있어 KIS 일반 명명 규칙(tymd/xhms/open/high/low/last/evol)으로
        // 추정 구현 — 실전 투입 전 KIS_MOCK_LOG_RAW 로 원문 재확인 필요.
        server.expect(requestTo(startsWith("$base/uapi/overseas-price/v1/quotations/inquire-time-itemchartprice")))
            .andExpect(header("tr_id", "HHDFS76950200"))
            .andRespond(
                withSuccess(
                    """{"rt_cd":"0","msg_cd":"0","msg1":"OK","output1":{},"output2":[
                        {"tymd":"20260929","xhms":"140000","open":"135.00","high":"136.50","low":"134.80","last":"136.30","evol":"12345"}
                    ]}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        val candles = broker.minuteCandles(Market.NASDAQ, "AAPL", LocalTime.NOON)

        assertEquals(
            MinuteCandle(
                LocalDate.of(2026, 9, 29),
                LocalTime.of(14, 0, 0),
                BigDecimal("135.00"),
                BigDecimal("136.50"),
                BigDecimal("134.80"),
                BigDecimal("136.30"),
                12345,
            ),
            candles.single(),
        )
    }

    @Test
    fun `국내 체결가 조회는 실측 필드명을 그대로 매핑한다`() {
        // 2026-09-29 삼성전자 실측 원문: 지정가 273,000원 매수가 실제로는 272,000원에 체결됨
        server.expect(requestTo(startsWith("$base/uapi/domestic-stock/v1/trading/inquire-daily-ccld")))
            .andExpect(header("tr_id", "VTTC0081R"))
            .andRespond(
                withSuccess(
                    """{"rt_cd":"0","msg_cd":"20310000","msg1":"모의투자 조회가 완료되었습니다.","output1":[
                        {"odno":"0000008307","tot_ccld_qty":"1","avg_prvs":"272000"}
                    ],"output2":{}}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        assertEquals(0, BigDecimal("272000").compareTo(broker.filledPrice(Market.KR, "0000008307")))
    }

    @Test
    fun `아직 체결 안 됐으면 null`() {
        server.expect(requestTo(startsWith("$base/uapi/domestic-stock/v1/trading/inquire-daily-ccld")))
            .andRespond(
                withSuccess(
                    """{"rt_cd":"0","msg_cd":"20310000","msg1":"완료","output1":[],"output2":{}}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        assertEquals(null, broker.filledPrice(Market.KR, "0000099999"))
    }

    @Test
    fun `해외 체결가는 VTTS3035R 결과에서 주문번호로 골라 체결단가를 돌려준다`() {
        server.expect(requestTo(startsWith("$base/uapi/overseas-stock/v1/trading/inquire-ccnl")))
            .andExpect(header("tr_id", "VTTS3035R"))
            .andRespond(
                ok(
                    """[{"odno":"0000000001","ft_ccld_qty":"1","ft_ccld_unpr3":"10.00"},
                        {"odno":"0000037508","ft_ccld_qty":"1","ft_ccld_unpr3":"149.2500"}]""",
                ),
            )

        assertEquals(0, BigDecimal("149.25").compareTo(broker.filledPrice(Market.AMEX, "0000037508")))
    }

    @Test
    fun `해외 체결수량이 0이면 null`() {
        server.expect(requestTo(startsWith("$base/uapi/overseas-stock/v1/trading/inquire-ccnl")))
            .andRespond(ok("""[{"odno":"0000000002","ft_ccld_qty":"0","ft_ccld_unpr3":"0"}]"""))

        assertEquals(null, broker.filledPrice(Market.AMEX, "0000000002"))
    }

    @Test
    fun `해외 주문이 체결내역에 없으면 null`() {
        server.expect(requestTo(startsWith("$base/uapi/overseas-stock/v1/trading/inquire-ccnl")))
            .andRespond(ok("""[{"odno":"0000000002","ft_ccld_qty":"1","ft_ccld_unpr3":"5"}]"""))

        assertEquals(null, broker.filledPrice(Market.AMEX, "9999999999"))
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

        val e = assertFailsWith<KisApiException> { broker.quote(Market.NASDAQ, "ZZZZ") }
        assert(e.message!!.contains("종목 없음"))
    }
}

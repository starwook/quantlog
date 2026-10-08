package com.quantlog.gateway.kis

import com.fasterxml.jackson.databind.ObjectMapper
import com.quantlog.gateway.broker.Market
import com.quantlog.gateway.broker.MinuteCandle
import com.quantlog.gateway.broker.OrderRequest
import com.quantlog.gateway.broker.Quote
import com.quantlog.gateway.broker.Side
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
    fun `rt_cd 가 0 이 아니면 KisApiException`() {
        server.expect(requestTo("$base/uapi/domestic-stock/v1/quotations/inquire-price?FID_COND_MRKT_DIV_CODE=J&FID_INPUT_ISCD=999999"))
            .andRespond(withSuccess("""{"rt_cd":"1","msg_cd":"EGW00123","msg1":"종목 없음"}""", MediaType.APPLICATION_JSON))

        val e = assertFailsWith<KisApiException> { broker.quote(Market.KR, "999999") }
        assert(e.message!!.contains("종목 없음"))
    }
}

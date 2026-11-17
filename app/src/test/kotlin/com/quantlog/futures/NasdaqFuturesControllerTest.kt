package com.quantlog.futures

import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.math.BigDecimal

@WebMvcTest(NasdaqFuturesController::class)
class NasdaqFuturesControllerTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockBean
    lateinit var client: NasdaqFuturesClient

    private fun candle(
        date: String,
        time: String?,
        close: String = "27319",
    ) = Candle(date, time, BigDecimal("27300"), BigDecimal("27320"), BigDecimal("27290"), BigDecimal(close), 5)

    @Test
    fun `기본은 1분봉이고 야후 전일 종가를 기준으로 그린다`() {
        Mockito.`when`(client.quote(false))
            .thenReturn(NasdaqFuturesQuote(listOf(candle("20261116", "233000"), candle("20261117", "020100")), BigDecimal("27284.25")))

        mvc.perform(get("/futures/nasdaq"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("id=\"fchart\"")))
            .andExpect(content().string(containsString(">20261116 23:30</span>")))
            .andExpect(content().string(containsString(">20261117 02:01</span>")))
            .andExpect(content().string(containsString("const baseline = 27284.25;")))
            .andExpect(content().string(containsString("nasdaq\\/candles")))
            .andExpect(content().string(containsString("const pollSeconds = 60;")))
    }

    @Test
    fun `일봉은 직전 봉 종가가 기준이고 자동 갱신하지 않는다`() {
        Mockito.`when`(client.quote(true))
            .thenReturn(NasdaqFuturesQuote(listOf(candle("20261113", null, "28034.75"), candle("20261117", null)), null))

        mvc.perform(get("/futures/nasdaq").param("chart", "D"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("const baseline = 28034.75;")))
            .andExpect(content().string(containsString("const pollUrl = null;")))
    }

    @Test
    fun `야후 조회가 실패해도 화면은 열린다`() {
        Mockito.`when`(client.quote(false)).thenThrow(IllegalStateException("야후 HTTP 429"))

        mvc.perform(get("/futures/nasdaq"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("야후 HTTP 429")))
            .andExpect(content().string(not(containsString("id=\"fchart\""))))
    }

    @Test
    fun `갱신용 봉은 최근 60개만 주고 실패하면 빈 목록이다`() {
        Mockito.`when`(client.quote(false))
            .thenReturn(NasdaqFuturesQuote((0 until 90).map { candle("20261117", "%02d%02d00".format(it / 60, it % 60)) }, null))
        mvc.perform(get("/futures/nasdaq/candles"))
            .andExpect(jsonPath("$.length()").value(60))
            .andExpect(jsonPath("$[0].close").value(27319.0))

        Mockito.`when`(client.quote(false)).thenThrow(IllegalStateException("막힘"))
        mvc.perform(get("/futures/nasdaq/candles")).andExpect(content().json("[]"))
    }
}

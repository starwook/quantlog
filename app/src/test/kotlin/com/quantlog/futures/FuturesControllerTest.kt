package com.quantlog.futures

import com.quantlog.ClockConfig
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

@WebMvcTest(FuturesController::class)
@Import(ClockConfig::class)
class FuturesControllerTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockBean
    lateinit var client: FuturesOptionsClient

    @MockBean
    lateinit var store: OptionSnapshotStore

    @MockBean
    lateinit var collector: OptionChainCollector

    @MockBean
    lateinit var night: NightFuturesRecorder

    private val nov = YearMonth.of(2026, 11)

    @Test
    fun `저장된 매물대와 선물을 그린다`() {
        val strike =
            OptionStrike(BigDecimal("940.00"), OptionQuote(BigDecimal("1.5"), 900, 10, 5), OptionQuote(BigDecimal("2.5"), 100, 0, 5))
        Mockito.`when`(store.latest(nov)).thenReturn(LocalDate.of(2026, 10, 13) to OptionBoard(listOf(strike), BigDecimal("939.44")))
        Mockito.`when`(client.futuresBoard()).thenReturn(listOf(mapOf("hts_kor_isnm" to "F 202612", "futs_prpr" to "941.85")))

        mvc.perform(get("/futures").param("maturity", "2026-11"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("콜 최대 (저항)")))
            .andExpect(content().string(not(containsString("939.44"))))
            .andExpect(content().string(containsString("2026-10-13")))
            .andExpect(content().string(containsString("F 202612")))
    }

    private val board = listOf(mapOf("futs_shrn_iscd" to "A01612", "hts_kor_isnm" to "F 202612"))

    private fun candle(
        date: String,
        time: String?,
    ) = Candle(date, time, BigDecimal("100"), BigDecimal("110"), BigDecimal("90"), BigDecimal("105"), 1)

    @Test
    fun `기본은 1분봉이고 앱이 쌓은 주간·야간 분봉을 그린다`() {
        Mockito.`when`(client.futuresBoard()).thenReturn(board)
        Mockito.`when`(
            night.recent("A01612"),
        ).thenReturn(listOf(candle("20261013", "154500"), candle("20261013", "180100"), candle("20261014", "020100")))

        mvc.perform(get("/futures"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("id=\"fchart\"")))
            .andExpect(content().string(containsString(">20261013 15:45</span>")))
            .andExpect(content().string(containsString(">20261014 02:01</span>")))
            .andExpect(content().string(containsString(">3</span>봉")))
            .andExpect(content().string(containsString("\"close\":105.0")))
    }

    @Test
    fun `야간 봉이 300개를 채워도 주간 종가 기준 등락률이 남는다`() {
        val nightBars =
            listOf(candle("20261013", "154500")) +
                (0 until 300).map { candle("20261014", "%02d%02d00".format(it / 60, it % 60)) }
        Mockito.`when`(client.futuresBoard()).thenReturn(board)
        Mockito.`when`(client.lastRegularQuote("A01612")).thenReturn(RegularQuote(BigDecimal("861.30"), BigDecimal("-8.78")))
        Mockito.`when`(night.recent("A01612")).thenReturn(nightBars)

        mvc.perform(get("/futures"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("주간 전일 대비 -8.78%")))
            .andExpect(content().string(containsString("861.3")))
    }

    @Test
    fun `일봉 탭은 한투 일봉을 그린다`() {
        val today = LocalDate.now(ZoneId.of("Asia/Seoul"))
        Mockito.`when`(client.futuresBoard()).thenReturn(board)
        Mockito.`when`(client.candles("A01612", false, today)).thenReturn(listOf(candle("20261013", null)))

        mvc.perform(get("/futures").param("chart", "D"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString(">1</span>봉")))
    }

    @Test
    fun `저장본이 없고 선물 조회가 실패해도 화면은 열린다`() {
        Mockito.`when`(client.futuresBoard()).thenThrow(IllegalStateException("게이트웨이 없음"))

        mvc.perform(get("/futures"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("저장본이 아직 없습니다")))
            .andExpect(content().string(containsString("게이트웨이 없음")))
    }

    @Test
    fun `지금 모으기는 수집을 뒤에서 시작한다`() {
        Mockito.`when`(collector.collectInBackground(nov)).thenReturn(true)

        mvc.perform(post("/futures/collect").param("maturity", "2026-11"))
            .andExpect(redirectedUrl("/futures?maturity=2026-11"))
            .andExpect(flash().attribute("done", containsString("모으기 시작")))
    }
}

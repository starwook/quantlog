package com.quantlog.futures

import com.quantlog.ClockConfig
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.math.BigDecimal
import java.time.YearMonth

@WebMvcTest(FuturesController::class)
@Import(ClockConfig::class)
class FuturesControllerTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockBean
    lateinit var client: FuturesOptionsClient

    private fun quote(oi: Long) = OptionQuote("201", BigDecimal("1.5"), oi, 10, 5)

    @Test
    fun `매물대와 선물 전광판을 그린다`() {
        val board = OptionBoard(listOf(OptionStrike(BigDecimal("350.00"), quote(900), quote(100), atm = true)))
        Mockito.`when`(client.optionBoard(any(YearMonth::class.java) ?: YearMonth.now())).thenReturn(board)
        Mockito.`when`(client.futuresBoard()).thenReturn(listOf(mapOf("futs_prpr" to "351.20")))

        mvc.perform(get("/futures").param("maturity", "2026-10"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("콜 최대 (저항)")))
            .andExpect(content().string(containsString("350.00")))
            .andExpect(content().string(containsString("351.20")))
    }

    @Test
    fun `조회가 실패해도 화면은 열리고 이유를 보여준다`() {
        Mockito.`when`(client.optionBoard(any(YearMonth::class.java) ?: YearMonth.now())).thenThrow(IllegalStateException("한투 오류"))
        Mockito.`when`(client.futuresBoard()).thenThrow(IllegalStateException("게이트웨이 없음"))

        mvc.perform(get("/futures"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("한투 오류")))
            .andExpect(content().string(containsString("게이트웨이 없음")))
    }
}

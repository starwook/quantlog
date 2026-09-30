package com.quantlog.watchlist

import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(SymbolStrategyController::class)
class SymbolStrategyControllerTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockBean
    lateinit var service: SymbolStrategyService

    @Test
    fun `설정 화면이 종목 값과 메뉴를 그린다`() {
        val samsung = SeedSymbol.SAMSUNG
        Mockito.`when`(
            service.all(),
        ).thenReturn(listOf(symbolStrategy(samsung.market, samsung.symbol, displayName = samsung.displayName, takeProfit = "0.5")))

        mvc.perform(get("/settings"))
            .andExpect(status().isOk)
            .andExpect(content().string(org.hamcrest.Matchers.containsString("삼성전자")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("value=\"0.5\"")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"/settings\"")))
    }

    @Test
    fun `저장하면 설정 화면으로 돌아간다`() {
        mvc.perform(post("/settings/KR/005930").param("autoTrade", "true").param("takeProfitPercent", "1"))
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/settings"))
    }
}

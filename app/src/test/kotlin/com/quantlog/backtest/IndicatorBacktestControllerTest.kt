package com.quantlog.backtest

import com.quantlog.broker.Market
import com.quantlog.broker.MinuteCandle
import com.quantlog.marketdata.MarketDataService
import com.quantlog.strategy.StrategyProperties
import com.quantlog.watchlist.SymbolStrategyService
import com.quantlog.watchlist.symbolStrategy
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

@WebMvcTest(IndicatorBacktestController::class)
@Import(IndicatorBacktestService::class)
@EnableConfigurationProperties(StrategyProperties::class)
class IndicatorBacktestControllerTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockBean
    lateinit var marketDataService: MarketDataService

    @MockBean
    lateinit var symbolStrategyService: SymbolStrategyService

    private val today = LocalDate.of(2026, 11, 4)
    private val yesterday = LocalDate.of(2026, 11, 3)

    private fun candles(
        day: LocalDate,
        vararg closes: Int,
    ) = closes.mapIndexed { i, close ->
        val c = BigDecimal(close)
        MinuteCandle(day, LocalTime.of(9, 0).plusMinutes(i.toLong()), c, c, c, c, 100)
    }

    private fun stub() {
        val strategy = symbolStrategy(Market.KR, "069500", displayName = "KODEX 200", takeProfit = "0.5", stopLoss = "2")
        Mockito.`when`(symbolStrategyService.find(Market.KR, "069500")).thenReturn(strategy)
        Mockito.`when`(symbolStrategyService.all()).thenReturn(listOf(strategy))
        Mockito.`when`(symbolStrategyService.displayName(Market.KR, "069500")).thenReturn("KODEX 200")
        Mockito.`when`(marketDataService.candleDates(Market.KR, "069500")).thenReturn(listOf(today, yesterday))
        Mockito.`when`(
            marketDataService.recentCandles(Market.KR, "069500", today),
        ).thenReturn(candles(today, 100, 99, 98, 97, 98, 100, 101, 102))
        Mockito.`when`(marketDataService.recentCandles(Market.KR, "069500", yesterday)).thenReturn(candles(yesterday, 100, 100, 100))
    }

    @Test
    fun `프리셋 목록·파라미터·합계·날짜별 결과를 그린다`() {
        stub()

        mvc.perform(get("/backtest/indicator/KR/069500").param("preset", "GOLDEN_CROSS").param("p_fast", "2").param("p_slow", "4"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("KODEX 200 지표 백테스트")))
            .andExpect(content().string(containsString("당일 신고가 돌파")))
            .andExpect(content().string(containsString("name=\"p_slow\"")))
            .andExpect(content().string(containsString("최대 낙폭")))
            .andExpect(content().string(containsString("2026-11-03")))
            .andExpect(content().string(containsString("매수 신호: MA2")))
    }

    @Test
    fun `매도 조건이 없는 프리셋은 매도 신호 옵션을 보여 주지 않는다`() {
        stub()

        mvc.perform(get("/backtest/indicator/KR/069500").param("preset", "STRONG_CLOSE"))
            .andExpect(status().isOk)
            .andExpect(content().string(not(containsString("매도 신호로도 팔기"))))
    }

    @Test
    fun `분봉이 없어도 화면은 열린다`() {
        val strategy = symbolStrategy(Market.KR, "069500", displayName = "KODEX 200")
        Mockito.`when`(symbolStrategyService.all()).thenReturn(listOf(strategy))
        Mockito.`when`(symbolStrategyService.displayName(Market.KR, "069500")).thenReturn("KODEX 200")
        Mockito.`when`(marketDataService.candleDates(Market.KR, "069500")).thenReturn(emptyList())

        mvc.perform(get("/backtest/indicator/KR/069500"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("쌓인 분봉이 없습니다")))
    }
}

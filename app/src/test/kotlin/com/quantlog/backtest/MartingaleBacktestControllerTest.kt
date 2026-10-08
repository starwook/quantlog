package com.quantlog.backtest

import com.quantlog.broker.Market
import com.quantlog.broker.MinuteCandle
import com.quantlog.marketdata.MarketDataService
import com.quantlog.strategy.MartingaleProperties
import com.quantlog.strategy.StrategyProperties
import com.quantlog.watchlist.SymbolStrategyService
import com.quantlog.watchlist.symbolStrategy
import org.hamcrest.Matchers.containsString
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

@WebMvcTest(MartingaleBacktestController::class)
@Import(MartingaleBacktestService::class)
@EnableConfigurationProperties(MartingaleProperties::class, StrategyProperties::class)
class MartingaleBacktestControllerTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockBean
    lateinit var marketDataService: MarketDataService

    @MockBean
    lateinit var symbolStrategyService: SymbolStrategyService

    private val date = LocalDate.of(2026, 10, 7)

    private fun candle(
        minute: Int,
        open: String,
        high: String,
        low: String,
        close: String,
    ) = MinuteCandle(
        date,
        LocalTime.of(9, 0).plusMinutes(minute.toLong()),
        BigDecimal(open),
        BigDecimal(high),
        BigDecimal(low),
        BigDecimal(close),
        100,
    )

    @Test
    fun `분봉이 있으면 사이클과 날짜별 비교, 조합 순위를 그린다`() {
        val strategy = symbolStrategy(Market.KR, "069500", displayName = "KODEX 200", martingale = true, takeProfit = "0.5", stopLoss = "2")
        Mockito.`when`(symbolStrategyService.find(Market.KR, "069500")).thenReturn(strategy)
        Mockito.`when`(symbolStrategyService.all()).thenReturn(listOf(strategy))
        Mockito.`when`(symbolStrategyService.displayName(Market.KR, "069500")).thenReturn("KODEX 200")
        Mockito.`when`(marketDataService.candleDates(Market.KR, "069500")).thenReturn(listOf(date))
        Mockito.`when`(marketDataService.recentCandles(Market.KR, "069500", date)).thenReturn(
            listOf(
                candle(0, "100", "100", "99.5", "99.5"),
                candle(1, "99.5", "101", "99.5", "101"),
            ),
        )

        mvc.perform(get("/backtest/martingale/KR/069500"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("KODEX 200 마틴게일 백테스트")))
            .andExpect(content().string(containsString("익절")))
            .andExpect(content().string(containsString("조합 순위")))
            .andExpect(content().string(containsString("고저폭")))
    }

    @Test
    fun `분봉이 없어도 화면은 열린다`() {
        Mockito.`when`(symbolStrategyService.displayName(Market.KR, "069500")).thenReturn("KODEX 200")
        Mockito.`when`(marketDataService.candleDates(Market.KR, "069500")).thenReturn(emptyList())
        Mockito.`when`(marketDataService.recentCandles(Market.KR, "069500", LocalDate.now(java.time.ZoneId.of("Asia/Seoul"))))
            .thenReturn(emptyList())

        mvc.perform(get("/backtest/martingale/KR/069500").param("submitted", "1").param("stop", ""))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("분봉 데이터가 없습니다")))
    }
}

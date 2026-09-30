package com.quantlog.chart

import com.quantlog.broker.Market
import com.quantlog.broker.MinuteCandle
import com.quantlog.broker.Side
import com.quantlog.marketdata.MarketDataService
import com.quantlog.position.Trade
import com.quantlog.position.TradeRepository
import com.quantlog.watchlist.symbolStrategyServiceOf
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChartControllerTest {
    private val kst = ZoneId.of("Asia/Seoul")
    private val today = LocalDate.now(kst)

    private fun candle(
        time: LocalTime,
        close: String,
    ) = MinuteCandle(today, time, BigDecimal(close), BigDecimal(close), BigDecimal(close), BigDecimal(close), 100)

    private fun trade(
        side: Side,
        executedAt: Instant,
        filledPrice: BigDecimal? = BigDecimal("10000"),
    ) = Trade(Market.KR, "005930", side, 1, BigDecimal("10000"), "1", "ok", "reason", filledPrice, executedAt)

    private fun controller(
        candles: List<MinuteCandle>,
        trades: List<Trade>,
    ): ChartController {
        val marketDataService = Mockito.mock(MarketDataService::class.java)
        Mockito.`when`(marketDataService.recentCandles(Market.KR, "005930", today)).thenReturn(candles)
        val tradeRepository = Mockito.mock(TradeRepository::class.java)
        Mockito.`when`(tradeRepository.findAllByMarketAndSymbolOrderByExecutedAtAsc(Market.KR, "005930")).thenReturn(trades)
        return ChartController(marketDataService, tradeRepository, symbolStrategyServiceOf())
    }

    @Test
    fun `분봉과 오늘 매매를 시간순 JSON 으로 돌려준다`() {
        val candles = listOf(candle(LocalTime.of(9, 0), "10000"), candle(LocalTime.of(9, 1), "10050"))
        val todayTrade = trade(Side.BUY, today.atTime(9, 1).atZone(kst).toInstant())
        val yesterdayTrade = trade(Side.SELL, today.minusDays(1).atTime(9, 1).atZone(kst).toInstant())

        val result = controller(candles, listOf(todayTrade, yesterdayTrade)).data(Market.KR, "005930")

        assertEquals(2, result.candles.size)
        assertTrue(result.candles[0].time < result.candles[1].time)
        // 어제 매매는 빠지고 오늘 매매만 남는다.
        assertEquals(1, result.trades.size)
        assertEquals("BUY", result.trades[0].side)
        assertEquals(0, BigDecimal("10000").compareTo(result.trades[0].price))
    }

    @Test
    fun `체결가가 없으면 지정가를 대신 쓴다`() {
        val trade = trade(Side.BUY, today.atTime(9, 0).atZone(kst).toInstant(), filledPrice = null)
        val result = controller(emptyList(), listOf(trade)).data(Market.KR, "005930")
        assertEquals(0, BigDecimal("10000").compareTo(result.trades[0].price))
    }

    @Test
    fun `분봉이 없으면 빈 목록을 돌려준다`() {
        val result = controller(emptyList(), emptyList()).data(Market.KR, "005930")
        assertTrue(result.candles.isEmpty())
        assertTrue(result.trades.isEmpty())
    }
}

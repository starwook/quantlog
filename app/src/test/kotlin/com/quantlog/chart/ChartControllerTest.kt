package com.quantlog.chart

import com.quantlog.broker.BrokerClient
import com.quantlog.broker.LatestPrices
import com.quantlog.broker.Market
import com.quantlog.broker.MinuteCandle
import com.quantlog.broker.Side
import com.quantlog.marketdata.MarketDataService
import com.quantlog.position.AccountHolding
import com.quantlog.position.AccountHoldingRepository
import com.quantlog.position.HoldingValuation
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
        previousClose: BigDecimal? = null,
        latestPrices: LatestPrices = LatestPrices(),
        holding: AccountHolding? = null,
    ): ChartController {
        val marketDataService = Mockito.mock(MarketDataService::class.java)
        Mockito.`when`(marketDataService.recentCandles(Market.KR, "005930", today)).thenReturn(candles)
        val tradeRepository = Mockito.mock(TradeRepository::class.java)
        Mockito.`when`(tradeRepository.findAllByMarketAndSymbolOrderByExecutedAtAsc(Market.KR, "005930")).thenReturn(trades)
        val broker = Mockito.mock(BrokerClient::class.java)
        Mockito.`when`(broker.previousClose(Market.KR, "005930")).thenReturn(previousClose)
        val holdings = Mockito.mock(AccountHoldingRepository::class.java)
        Mockito.`when`(holdings.findByMarketAndSymbol(Market.KR, "005930")).thenReturn(holding)
        return ChartController(
            marketDataService,
            tradeRepository,
            symbolStrategyServiceOf(),
            broker,
            holdings,
            latestPrices,
            HoldingValuation(latestPrices),
        )
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

    @Test
    fun `전일 종가를 함께 돌려준다`() {
        val result = controller(emptyList(), emptyList(), BigDecimal("9900")).data(Market.KR, "005930")
        assertEquals(0, BigDecimal("9900").compareTo(result.previousClose))
    }

    @Test
    fun `현재가는 분봉이 아니라 실시간 틱을 쓴다`() {
        val prices = LatestPrices().apply { record("005930", BigDecimal("9800"), Instant.now()) }
        val staleCandle = listOf(candle(LocalTime.of(15, 30), "9900"))

        val result = controller(staleCandle, emptyList(), latestPrices = prices).data(Market.KR, "005930")

        assertEquals(0, BigDecimal("9800").compareTo(result.currentPrice))
    }

    @Test
    fun `틱이 없으면 보유 종목의 잔고 현재가를 쓴다`() {
        val held = AccountHolding(Market.KR, "005930", 1, BigDecimal("9000"), BigDecimal("9700"))

        val result = controller(emptyList(), emptyList(), holding = held).data(Market.KR, "005930")

        assertEquals(0, BigDecimal("9700").compareTo(result.currentPrice))
    }

    @Test
    fun `틱도 보유도 없으면 현재가는 null 이다`() {
        assertEquals(null, controller(emptyList(), emptyList()).data(Market.KR, "005930").currentPrice)
    }
}

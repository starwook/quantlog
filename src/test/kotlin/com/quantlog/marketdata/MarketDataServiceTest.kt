package com.quantlog.marketdata

import com.quantlog.broker.BrokerClient
import com.quantlog.broker.BuyingPower
import com.quantlog.broker.Holding
import com.quantlog.broker.Market
import com.quantlog.broker.MinuteCandle
import com.quantlog.broker.OrderReceipt
import com.quantlog.broker.OrderRequest
import com.quantlog.broker.Quote
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.assertEquals

class MarketDataServiceTest {
    private class FakeBroker(private val candles: List<MinuteCandle>) : BrokerClient {
        override fun quote(
            market: Market,
            symbol: String,
        ) = Quote(BigDecimal.ONE, BigDecimal.ONE)

        override fun minuteCandles(
            market: Market,
            symbol: String,
            atTime: LocalTime,
        ) = candles

        override fun filledPrice(
            market: Market,
            orderNo: String,
        ): BigDecimal? = null

        override fun buyingPower(
            market: Market,
            symbol: String,
            price: BigDecimal,
        ) = BuyingPower("KRW", BigDecimal.ZERO, BigDecimal.ZERO)

        override fun holdings(market: Market) = emptyList<Holding>()

        override fun placeOrder(order: OrderRequest) = OrderReceipt("1", "ok")
    }

    private val candle =
        MinuteCandle(
            date = LocalDate.of(2026, 9, 29),
            time = LocalTime.of(10, 0, 0),
            open = BigDecimal("271500"),
            high = BigDecimal("272000"),
            low = BigDecimal("271000"),
            close = BigDecimal("272000"),
            volume = 46062,
        )

    @Test
    fun `처음 받은 분봉은 그대로 저장한다`() {
        val repository = Mockito.mock(MinuteCandleRepository::class.java)
        Mockito.`when`(
            repository.existsByMarketAndSymbolAndTradeDateAndTradeTime(Market.KR, "005930", candle.date, candle.time),
        ).thenReturn(false)
        val saved =
            MinuteCandleEntity(
                Market.KR, "005930", candle.date, candle.time,
                candle.open, candle.high, candle.low, candle.close, candle.volume,
            )
        Mockito.`when`(repository.save(Mockito.any(MinuteCandleEntity::class.java))).thenReturn(saved)

        val service = MarketDataService(FakeBroker(listOf(candle)), MinuteCandleStore(repository))
        val result = service.fetchAndStoreRecentMinutes(Market.KR, "005930", LocalTime.of(10, 0, 0))

        assertEquals(1, result.size)
        Mockito.verify(repository).save(Mockito.any(MinuteCandleEntity::class.java))
    }

    @Test
    fun `이미 저장된 분봉은 다시 저장하지 않는다`() {
        val repository = Mockito.mock(MinuteCandleRepository::class.java)
        Mockito.`when`(
            repository.existsByMarketAndSymbolAndTradeDateAndTradeTime(Market.KR, "005930", candle.date, candle.time),
        ).thenReturn(true)

        val service = MarketDataService(FakeBroker(listOf(candle)), MinuteCandleStore(repository))
        val result = service.fetchAndStoreRecentMinutes(Market.KR, "005930", LocalTime.of(10, 0, 0))

        assertEquals(0, result.size)
        Mockito.verify(repository, Mockito.never()).save(Mockito.any(MinuteCandleEntity::class.java))
    }

    @Test
    fun `저장된 분봉을 오래된 것부터 최신 순으로 돌려준다`() {
        val repository = Mockito.mock(MinuteCandleRepository::class.java)
        val older =
            MinuteCandleEntity(
                Market.KR, "005930", candle.date, LocalTime.of(9, 0, 0),
                candle.open, candle.high, candle.low, candle.close, candle.volume,
            )
        val newer =
            MinuteCandleEntity(
                Market.KR, "005930", candle.date, LocalTime.of(9, 1, 0),
                candle.open, candle.high, candle.low, candle.close, candle.volume,
            )
        // repository 는 최신순(desc)으로 내려주는데, 서비스는 오래된 것부터로 뒤집어 줘야 한다.
        Mockito.`when`(
            repository.findAllByMarketAndSymbolAndTradeDateOrderByTradeTimeDesc(Market.KR, "005930", candle.date),
        ).thenReturn(listOf(newer, older))

        val service = MarketDataService(FakeBroker(emptyList()), MinuteCandleStore(repository))
        val result = service.recentCandles(Market.KR, "005930", candle.date)

        assertEquals(listOf(LocalTime.of(9, 0, 0), LocalTime.of(9, 1, 0)), result.map { it.time })
    }
}

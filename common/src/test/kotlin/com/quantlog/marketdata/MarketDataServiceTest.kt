package com.quantlog.marketdata

import com.quantlog.broker.Market
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.assertEquals

class MarketDataServiceTest {
    private val date = LocalDate.of(2026, 9, 29)
    private val price = BigDecimal("272000")

    @Test
    fun `저장된 분봉을 오래된 것부터 최신 순으로 돌려준다`() {
        val repository = Mockito.mock(MinuteCandleRepository::class.java)
        val older =
            MinuteCandleEntity(
                Market.KR, "005930", date, LocalTime.of(9, 0, 0),
                price, price, price, price, 1L,
            )
        val newer =
            MinuteCandleEntity(
                Market.KR, "005930", date, LocalTime.of(9, 1, 0),
                price, price, price, price, 1L,
            )
        // repository 는 최신순(desc)으로 내려주는데, 서비스는 오래된 것부터로 뒤집어 줘야 한다.
        Mockito.`when`(
            repository.findAllByMarketAndSymbolAndTradeDateOrderByTradeTimeDesc(Market.KR, "005930", date),
        ).thenReturn(listOf(newer, older))

        val service = MarketDataService(MinuteCandleStore(repository))
        val result = service.recentCandles(Market.KR, "005930", date)

        assertEquals(listOf(LocalTime.of(9, 0, 0), LocalTime.of(9, 1, 0)), result.map { it.time })
    }
}

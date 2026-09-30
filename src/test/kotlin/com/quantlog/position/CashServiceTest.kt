package com.quantlog.position

import com.quantlog.broker.BrokerClient
import com.quantlog.broker.BuyingPower
import com.quantlog.broker.Market
import com.quantlog.broker.Quote
import com.quantlog.watchlist.symbolStrategy
import com.quantlog.watchlist.symbolStrategyServiceOf
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CashServiceTest {
    private val broker = Mockito.mock(BrokerClient::class.java)
    private val service =
        CashService(
            broker,
            symbolStrategyServiceOf(
                symbolStrategy(Market.KR, "005930"),
                symbolStrategy(Market.AMEX, "SOXL"),
            ),
        )
    private val t0 = Instant.parse("2026-09-30T01:00:00Z")
    private val price = BigDecimal("270000")

    private fun stubKr(amount: String) {
        Mockito.`when`(broker.quote(Market.KR, "005930")).thenReturn(Quote(price, BigDecimal("500")))
        Mockito.`when`(broker.buyingPower(Market.KR, "005930", price))
            .thenReturn(BuyingPower("KRW", BigDecimal(amount), BigDecimal.ZERO))
    }

    @Test
    fun `해당 통화로 등록된 종목 기준으로 주문 가능 금액을 조회한다`() {
        stubKr("9000000")

        val cash = service.orderableCash("KRW", t0)

        assertEquals(0, BigDecimal("9000000").compareTo(cash?.amount))
        assertFalse(cash!!.stale)
    }

    @Test
    fun `30초 안에 다시 부르면 KIS 를 다시 부르지 않는다`() {
        stubKr("9000000")

        service.orderableCash("KRW", t0)
        service.orderableCash("KRW", t0.plusSeconds(29))

        verify(broker, times(1)).buyingPower(Market.KR, "005930", price)
    }

    @Test
    fun `30초가 지나면 다시 조회한다`() {
        stubKr("9000000")
        service.orderableCash("KRW", t0)
        stubKr("8000000")

        val cash = service.orderableCash("KRW", t0.plusSeconds(31))

        assertEquals(0, BigDecimal("8000000").compareTo(cash?.amount))
    }

    @Test
    fun `조회가 실패하면 마지막 성공 값을 갱신 실패 표시와 함께 돌려준다`() {
        stubKr("9000000")
        service.orderableCash("KRW", t0)
        Mockito.`when`(broker.buyingPower(Market.KR, "005930", price)).thenThrow(RuntimeException("KIS 오류"))

        val cash = service.orderableCash("KRW", t0.plusSeconds(31))

        assertEquals(0, BigDecimal("9000000").compareTo(cash?.amount))
        assertTrue(cash!!.stale)
    }

    @Test
    fun `조회가 실패했는데 캐시도 없으면 null 이다`() {
        Mockito.`when`(broker.quote(Market.KR, "005930")).thenThrow(RuntimeException("KIS 오류"))

        assertNull(service.orderableCash("KRW", t0))
    }

    @Test
    fun `그 통화로 등록된 종목이 없으면 KIS 를 부르지 않고 null 이다`() {
        val onlyKr = CashService(broker, symbolStrategyServiceOf(symbolStrategy(Market.KR, "005930")))

        assertNull(onlyKr.orderableCash("USD", t0))
        Mockito.verifyNoInteractions(broker)
    }
}

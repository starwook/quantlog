package com.quantlog.gateway.paper

import com.quantlog.gateway.broker.Market
import com.quantlog.gateway.broker.OrderRequest
import com.quantlog.gateway.broker.Quote
import com.quantlog.gateway.broker.Side
import com.quantlog.gateway.kis.KisMockBroker
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyCollection
import org.mockito.Mockito
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PaperBrokerTest {
    private val stored = mutableListOf<PaperOrder>()

    // 삼성전자 273,000원 / 호가 500 → 매수 273,500, 매도 272,500(거래세 0.2% 반영 전).
    private val etfs = mutableSetOf<String>()

    private fun broker(properties: PaperProperties = PaperProperties()): PaperBroker {
        val kis = Mockito.mock(KisMockBroker::class.java)
        Mockito.`when`(kis.quote(Market.KR, "005930")).thenReturn(Quote(BigDecimal("273000"), BigDecimal("500")))
        val repository = Mockito.mock(PaperOrderRepository::class.java)
        Mockito.`when`(repository.save(Mockito.any(PaperOrder::class.java))).thenAnswer {
            (it.arguments[0] as PaperOrder).also { order ->
                PaperOrder::class.java.getDeclaredField("id").apply { isAccessible = true }.set(order, stored.size + 1L)
                stored += order
            }
        }
        Mockito.`when`(repository.findAllByMarketInOrderByIdAsc(anyCollection())).thenAnswer { stored.toList() }
        Mockito.`when`(repository.findById(Mockito.anyLong())).thenAnswer {
            java.util.Optional.ofNullable(stored.getOrNull((it.arguments[0] as Long).toInt() - 1))
        }
        return PaperBroker(kis, repository, properties) { _, symbol -> symbol in etfs }
    }

    private fun order(
        side: Side,
        quantity: Int,
        limit: String,
    ) = OrderRequest(Market.KR, "005930", side, quantity, BigDecimal(limit))

    @Test
    fun `매수는 현재가 위 1틱에, 매도는 아래 1틱에 체결된다`() {
        val broker = broker(PaperProperties(krStockSellTaxPercent = BigDecimal.ZERO))
        val buy = broker.placeOrder(order(Side.BUY, 2, "274000"))
        assertEquals(0, BigDecimal("273500").compareTo(broker.filledPrice(Market.KR, buy.orderNo)))
        val sell = broker.placeOrder(order(Side.SELL, 2, "272500"))
        assertEquals(0, BigDecimal("272500").compareTo(broker.filledPrice(Market.KR, sell.orderNo)))
    }

    @Test
    fun `보유 수량과 평단과 현금이 기록에서 계산된다`() {
        val broker = broker()
        broker.placeOrder(order(Side.BUY, 1, "274000"))
        broker.placeOrder(order(Side.BUY, 1, "274000"))
        val holding = broker.holdings(Market.KR).single()
        assertEquals(0, BigDecimal(2).compareTo(holding.quantity))
        assertEquals(0, BigDecimal("273500").compareTo(holding.averagePrice))
        assertEquals(
            0,
            BigDecimal(
                "10000000",
            ).subtract(BigDecimal("547000")).compareTo(broker.buyingPower(Market.KR, "005930", BigDecimal("273000")).orderableAmount),
        )
    }

    @Test
    fun `국내 주식 매도에는 거래세가 빠지고 ETF 는 면제된다`() {
        val broker = broker(PaperProperties(krStockSellTaxPercent = BigDecimal("1")))
        broker.placeOrder(order(Side.BUY, 2, "274000"))
        val stock = broker.placeOrder(order(Side.SELL, 1, "272500"))
        assertEquals(0, BigDecimal("269775").compareTo(broker.filledPrice(Market.KR, stock.orderNo)))
        etfs += "005930"
        val etf = broker.placeOrder(order(Side.SELL, 1, "272500"))
        assertEquals(0, BigDecimal("272500").compareTo(broker.filledPrice(Market.KR, etf.orderNo)))
    }

    @Test
    fun `지정가가 호가에 못 미치면 거부된다`() {
        val broker = broker()
        assertFailsWith<IllegalStateException> { broker.placeOrder(order(Side.BUY, 1, "273000")) }
        assertEquals(0, stored.size)
    }

    @Test
    fun `현금이나 수량이 모자라면 거부된다`() {
        val broker = broker()
        assertFailsWith<IllegalStateException> { broker.placeOrder(order(Side.BUY, 100, "274000")) }
        assertFailsWith<IllegalStateException> { broker.placeOrder(order(Side.SELL, 1, "272500")) }
    }
}

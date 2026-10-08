package com.quantlog.gateway.record

import com.quantlog.gateway.broker.BrokerClient
import com.quantlog.gateway.broker.BuyingPower
import com.quantlog.gateway.broker.CancelRequest
import com.quantlog.gateway.broker.Market
import com.quantlog.gateway.broker.OrderReceipt
import com.quantlog.gateway.broker.OrderRequest
import com.quantlog.gateway.broker.Quote
import com.quantlog.gateway.broker.Side
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import java.math.BigDecimal

class OrderServiceTest {
    private val rows = mutableMapOf<String, BrokerOrder>()
    private val repository =
        Mockito.mock(BrokerOrderRepository::class.java).also { repo ->
            Mockito.`when`(repo.findByRequestId(Mockito.anyString())).thenAnswer { rows[it.getArgument<String>(0)] }
            Mockito.`when`(repo.save(Mockito.any(BrokerOrder::class.java))).thenAnswer {
                val row = it.getArgument<BrokerOrder>(0)
                rows[row.requestId] = row
                row
            }
        }

    private class FakeBroker : BrokerClient {
        var placed = 0
        var canceled = 0
        var failWith: Exception? = null

        override fun buyingPower(
            market: Market,
            symbol: String,
            price: BigDecimal,
        ) = BuyingPower("KRW", BigDecimal.TEN, BigDecimal.ONE)

        override fun minuteCandles(
            market: Market,
            symbol: String,
            atTime: java.time.LocalTime,
        ) = emptyList<com.quantlog.gateway.broker.MinuteCandle>()

        override fun filledPrice(
            market: Market,
            orderNo: String,
        ): BigDecimal? = null

        override fun holdings(market: Market) = emptyList<com.quantlog.gateway.broker.Holding>()

        override fun quote(
            market: Market,
            symbol: String,
        ) = Quote(BigDecimal.TEN, BigDecimal.ONE)

        override fun placeOrder(order: OrderRequest): OrderReceipt {
            placed++
            failWith?.let { throw it }
            return OrderReceipt("0001", "ok", "06010")
        }

        override fun cancelOrder(request: CancelRequest) {
            canceled++
            failWith?.let { throw it }
        }
    }

    private val broker = FakeBroker()
    private val service = OrderService(broker, repository)
    private val order = OrderRequest(Market.KR, "005930", Side.BUY, 1, BigDecimal("70000"))

    @Test
    fun `같은 요청 ID 를 다시 보내면 증권사에 또 내지 않고 저장된 접수 결과를 돌려준다`() {
        val first = service.place("r1", order)
        val second = service.place("r1", order)

        assertEquals(first, second)
        assertEquals(1, broker.placed)
        assertEquals(BrokerOrder.SENT, rows.getValue("r1").status)
    }

    @Test
    fun `보내는 중에 응답을 못 받은 요청 ID 는 맹목적으로 다시 보내지 않는다`() {
        rows["r2"] = BrokerOrder("r2", "PLACE", "KR", "005930", "BUY", 1, BigDecimal("70000"), null, BrokerOrder.SENDING)

        assertThrows<OrderResultUnknownException> { service.place("r2", order) }
        assertEquals(0, broker.placed)
    }

    @Test
    fun `증권사가 실패하면 FAILED 로 남기고 같은 ID 재요청은 실패를 그대로 돌려준다`() {
        broker.failWith = IllegalStateException("한도 초과")

        assertThrows<IllegalStateException> { service.place("r3", order) }
        assertEquals(BrokerOrder.FAILED, rows.getValue("r3").status)
        broker.failWith = null

        assertThrows<OrderFailedException> { service.place("r3", order) }
        assertEquals(1, broker.placed)
        // 새 요청 ID 로는 새로 시도한다.
        assertEquals("0001", service.place("r4", order).orderNo)
    }

    @Test
    fun `취소도 같은 요청 ID 면 한 번만 낸다`() {
        val cancel = CancelRequest(Market.KR, "005930", "0001", "06010", 1, BigDecimal("70000"))
        service.cancel("c1", cancel)
        service.cancel("c1", cancel)

        assertEquals(1, broker.canceled)
        assertTrue(rows.getValue("c1").status == BrokerOrder.SENT)
    }
}

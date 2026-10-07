package com.quantlog.trading

import com.quantlog.broker.Market
import com.quantlog.broker.PriceTick
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal
import java.util.concurrent.Executor
import kotlin.test.assertEquals

class MartingaleTickListenerTest {
    private fun listener(
        service: MartingaleService,
        enabled: Boolean = true,
    ) = MartingaleTickListener(service, EntryProperties(enabled = enabled)).apply { executor = Executor { it.run() } }

    @Test
    fun `국내 틱이 오면 그 종목을 틱 가격과 함께 마틴게일 판정한다`() {
        val service = Mockito.mock(MartingaleService::class.java)

        listener(service).onTick(PriceTick(Market.KR, "005930", BigDecimal("10000")))

        val call = Mockito.mockingDetails(service).invocations.single()
        assertEquals("checkSymbol", call.method.name)
        assertEquals(listOf(Market.KR, "005930", BigDecimal("10000")), call.arguments.take(3))
    }

    @Test
    fun `진입이 꺼져 있거나 국내 틱이 아니면 무시한다`() {
        val service = Mockito.mock(MartingaleService::class.java)

        listener(service, enabled = false).onTick(PriceTick(Market.KR, "005930", BigDecimal("10000")))
        listener(service).onTick(PriceTick(Market.NASDAQ, "AAPL", BigDecimal("190")))

        assertEquals(0, Mockito.mockingDetails(service).invocations.size)
    }
}

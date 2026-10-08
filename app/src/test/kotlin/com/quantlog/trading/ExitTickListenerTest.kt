package com.quantlog.trading

import com.quantlog.broker.Market
import com.quantlog.broker.PriceTick
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal
import java.util.concurrent.Executor
import kotlin.test.assertEquals

class ExitTickListenerTest {
    private val tick = PriceTick(Market.KR, "005930", BigDecimal("274500"))

    private fun listener(
        service: ExitService,
        enabled: Boolean,
    ) = ExitTickListener(service, ExitProperties(enabled = enabled)).apply { executor = Executor { it.run() } }

    @Test
    fun `틱이 오면 그 종목을 청산 판정한다`() {
        val service = Mockito.mock(ExitService::class.java)
        listener(service, enabled = true).onTick(tick)
        val call = Mockito.mockingDetails(service).invocations.single()
        assertEquals("checkSymbol", call.method.name)
        assertEquals(listOf(Market.KR, "005930"), call.arguments.take(2))
    }

    @Test
    fun `청산이 꺼져 있으면 틱을 무시한다`() {
        val service = Mockito.mock(ExitService::class.java)
        listener(service, enabled = false).onTick(tick)
        assertEquals(0, Mockito.mockingDetails(service).invocations.size)
    }
}

package com.quantlog.gatewayclient

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.quantlog.broker.CandleUpdated
import com.quantlog.broker.Market
import com.quantlog.broker.PriceTick
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.math.BigDecimal
import java.time.LocalTime

class GatewayStreamClientTest {
    private val published = mutableListOf<Any>()
    private val client =
        GatewayStreamClient(
            GatewayClientProperties(),
            jacksonObjectMapper().findAndRegisterModules(),
            ApplicationEventPublisher { published += it },
        )

    @Test
    fun `tick 은 PriceTick 이벤트로 다시 발행한다`() {
        client.handle("""{"type":"tick","market":"KR","symbol":"005930","price":70100}""")

        val tick = published.single() as PriceTick
        assertEquals(Market.KR, tick.market)
        assertEquals(0, BigDecimal("70100").compareTo(tick.price))
    }

    @Test
    fun `candle 은 CandleUpdated 이벤트로 다시 발행한다`() {
        client.handle(
            """{"type":"candle","market":"KR","symbol":"005930",""" +
                """"candle":{"date":"2026-10-08","time":"09:01:00","open":1,"high":2,"low":1,"close":2,"volume":10}}""",
        )

        val update = published.single() as CandleUpdated
        assertEquals(LocalTime.of(9, 1), update.candle.time)
        assertEquals(10L, update.candle.volume)
    }

    @Test
    fun `fill 과 balance 알림은 GatewayNotified 로 발행하고 ping 은 무시한다`() {
        client.handle("""{"type":"fill","id":5}""")
        client.handle("""{"type":"balance","seq":3}""")
        client.handle("""{"type":"ping","at":"2026-10-08T00:00:00Z"}""")

        assertEquals(listOf(GatewayNotified("fill"), GatewayNotified("balance")), published)
        assertTrue(published.none { it is PriceTick })
    }
}

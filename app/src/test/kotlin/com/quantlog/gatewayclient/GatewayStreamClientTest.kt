package com.quantlog.gatewayclient

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.quantlog.broker.CandleUpdated
import com.quantlog.broker.LatestPrices
import com.quantlog.broker.Market
import com.quantlog.broker.PriceTick
import com.quantlog.marketdata.MinuteCandleEntity
import com.quantlog.marketdata.MinuteCandleRepository
import com.quantlog.marketdata.MinuteCandleStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.context.ApplicationEventPublisher
import java.math.BigDecimal
import java.time.LocalTime

class GatewayStreamClientTest {
    private val published = mutableListOf<Any>()
    private val saved = mutableListOf<MinuteCandleEntity>()
    private val events = ApplicationEventPublisher { published += it }
    private val repository =
        Mockito.mock(MinuteCandleRepository::class.java).also { repo ->
            Mockito.`when`(repo.save(Mockito.any(MinuteCandleEntity::class.java))).thenAnswer {
                (it.arguments[0] as MinuteCandleEntity).also(saved::add)
            }
        }
    private val store = MinuteCandleStore(repository)
    private val client =
        GatewayStreamClient(
            GatewayClientProperties(),
            jacksonObjectMapper().findAndRegisterModules(),
            events,
            RealtimeCandleBuilder(store, events, LatestPrices()),
        )

    private fun raw(
        time: String,
        price: Int,
        volume: Int,
    ): String {
        val fields = MutableList(46) { "0" }
        fields[0] = "005930"
        fields[1] = time
        fields[2] = price.toString()
        fields[12] = volume.toString()
        return """{"type":"raw","data":"0|H0STCNT0|001|${fields.joinToString("^")}"}"""
    }

    @Test
    fun `raw 시세 원문은 PriceTick 과 CandleUpdated 로 해석해 발행한다`() {
        client.handle(raw("090130", 70100, 5))

        val tick = published.filterIsInstance<PriceTick>().single()
        assertEquals(Market.KR, tick.market)
        assertEquals(0, BigDecimal("70100").compareTo(tick.price))
        val candle = published.filterIsInstance<CandleUpdated>().single().candle
        assertEquals(LocalTime.of(9, 1), candle.time)
        assertEquals(5L, candle.volume)
    }

    @Test
    fun `분이 바뀌면 같은 분 안의 틱을 합치고 끝난 분봉은 저장하되 중간부터 본 첫 분봉은 저장하지 않는다`() {
        client.handle(raw("090130", 100, 5))
        client.handle(raw("090145", 110, 3))
        client.handle(raw("090200", 105, 1)) // 첫 분봉(앞부분 모름) 종료 — 저장 안 함
        assertTrue(saved.isEmpty())

        client.handle(raw("090300", 90, 2)) // 두 번째 분봉 종료 — 저장
        val candle = saved.single()
        assertEquals(LocalTime.of(9, 2), candle.tradeTime)
        assertEquals(0, BigDecimal("105").compareTo(candle.close))
        assertEquals(1L, candle.volume)
    }

    @Test
    fun `시세가 아닌 raw 메시지는 무시한다`() {
        client.handle("""{"type":"raw","data":"0|H0STCNI9|001|a^b"}""")

        assertTrue(published.isEmpty())
    }

    @Test
    fun `fill 과 balance 알림은 GatewayNotified 로 발행하고 ping 은 무시한다`() {
        client.handle("""{"type":"fill","id":5}""")
        client.handle("""{"type":"balance","id":3}""")
        client.handle("""{"type":"ping","at":"2026-10-08T00:00:00Z"}""")

        assertEquals(listOf(GatewayNotified("fill"), GatewayNotified("balance")), published)
        assertTrue(published.none { it is PriceTick })
    }
}

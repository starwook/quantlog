package com.quantlog.gatewayclient

import com.quantlog.broker.LatestPrices
import com.quantlog.marketdata.MinuteCandleEntity
import com.quantlog.marketdata.MinuteCandleRepository
import com.quantlog.marketdata.MinuteCandleStore
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals

/**
 * 2026-10-26: 금요일 마지막 분봉(15:59, 종가 5,255)이 월요일 첫 틱 때 끝나면서 월요일 날짜로 저장돼, 차트가 월요일 현재가를 5,255 로 보여 줬다.
 */
class RealtimeCandleBuilderTest {
    private val saved = mutableListOf<MinuteCandleEntity>()
    private val repository =
        Mockito.mock(MinuteCandleRepository::class.java).also { repo ->
            Mockito.`when`(repo.save(Mockito.any(MinuteCandleEntity::class.java))).thenAnswer {
                (it.arguments[0] as MinuteCandleEntity).also(saved::add)
            }
        }
    private var now: Instant = Instant.parse("2026-10-23T06:58:10Z") // 금 15:58:10 KST
    private val clock =
        object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC

            override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)

            override fun instant(): Instant = now
        }
    private val builder = RealtimeCandleBuilder(MinuteCandleStore(repository), { }, LatestPrices(), clock)

    private fun trade(
        time: String,
        price: Int,
    ) = RealtimeTrade("233740", LocalTime.parse(time), BigDecimal(price), 10)

    @Test
    fun `전 거래일 마지막 분봉은 다음 거래일 첫 틱에 끝나도 그 분봉이 시작된 날짜로 저장한다`() {
        builder.onTrade(trade("15:58:10", 5255)) // 앞부분을 모르는 첫 분봉
        now = Instant.parse("2026-10-23T06:59:05Z")
        builder.onTrade(trade("15:59:05", 5255)) // 금 15:59 분봉 시작
        now = Instant.parse("2026-10-25T23:30:00Z") // 월 08:30 KST
        builder.onTrade(trade("08:30:00", 5255))

        val friday = saved.single()
        assertEquals(LocalDate.parse("2026-10-23"), friday.tradeDate)
        assertEquals(LocalTime.of(15, 59), friday.tradeTime)
    }

    @Test
    fun `날짜가 바뀌면 같은 시각이라도 새 분봉으로 시작한다`() {
        builder.onTrade(trade("15:58:10", 5255))
        builder.onTrade(trade("15:59:05", 5255))
        now = Instant.parse("2026-10-26T06:59:05Z") // 월 15:59 KST — 같은 15:59 지만 다른 날
        builder.onTrade(trade("15:59:05", 4900))

        assertEquals(LocalDate.parse("2026-10-23"), saved.single().tradeDate)
    }
}

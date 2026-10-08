package com.quantlog.gatewayclient

import com.quantlog.broker.CandleUpdated
import com.quantlog.broker.LatestPrices
import com.quantlog.broker.Market
import com.quantlog.broker.MinuteCandle
import com.quantlog.broker.PriceTick
import com.quantlog.marketdata.MinuteCandleStore
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

private val KST = ZoneId.of("Asia/Seoul")

/**
 * 실시간 체결 한 건마다 최신가를 기록하고([LatestPrices]) [PriceTick] 을 내고, 1분 단위로 묶어 진행 중인 분봉을 [CandleUpdated] 로 낸다. 끝난 분봉은 DB 에 저장한다
 * (게이트웨이의 REST 수집과 saveIfNew 로 겹치지 않는다).
 * 앱이 중간에 켜졌다면 그 종목의 첫 분봉은 앞부분이 빠진 것이라 저장하지 않는다(REST 수집분이 정확하다).
 */
@Component
class RealtimeCandleBuilder(
    private val store: MinuteCandleStore,
    private val events: ApplicationEventPublisher,
    private val latestPrices: LatestPrices,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val inProgress = ConcurrentHashMap<String, MutableCandle>()

    fun onTrade(trade: RealtimeTrade) {
        latestPrices.record(trade.symbol, trade.price, clock.instant())
        events.publishEvent(PriceTick(Market.KR, trade.symbol, trade.price))
        var finished: MutableCandle? = null
        val minute = trade.time.withSecond(0).withNano(0)
        val current =
            inProgress.compute(trade.symbol) { _, existing ->
                if (existing == null || existing.minute != minute) {
                    finished = existing
                    MutableCandle(minute, trade.price, partial = existing == null).apply { volume += trade.volume }
                } else {
                    existing.apply {
                        high = high.max(trade.price)
                        low = low.min(trade.price)
                        close = trade.price
                        volume += trade.volume
                    }
                }
            }!!
        finished?.takeUnless { it.partial }?.let { store.saveIfNew(Market.KR, trade.symbol, it.toCandle()) }
        events.publishEvent(CandleUpdated(Market.KR, trade.symbol, current.toCandle()))
    }

    private fun MutableCandle.toCandle() = MinuteCandle(LocalDate.now(KST), minute, open, high, low, close, volume)

    private class MutableCandle(val minute: LocalTime, price: BigDecimal, val partial: Boolean) {
        val open: BigDecimal = price
        var high: BigDecimal = price
        var low: BigDecimal = price
        var close: BigDecimal = price
        var volume: Long = 0
    }
}

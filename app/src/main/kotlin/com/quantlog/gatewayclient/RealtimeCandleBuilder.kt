package com.quantlog.gatewayclient

import com.quantlog.broker.CandleUpdated
import com.quantlog.broker.LatestPrices
import com.quantlog.broker.Market
import com.quantlog.broker.MinuteCandle
import com.quantlog.broker.PriceTick
import com.quantlog.common.KST
import com.quantlog.marketdata.MinuteCandleStore
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.ConcurrentHashMap

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
        val date = LocalDate.now(clock.withZone(KST))
        val minute = trade.time.withSecond(0).withNano(0)
        val current =
            inProgress.compute(trade.symbol) { _, existing ->
                // 날짜도 비교한다 — 전 거래일 마지막 분봉(예: 금요일 15:59)이 다음 거래일 첫 틱 때 끝나면서 그날 날짜로 저장되던 문제(2026-10-26).
                if (existing == null || existing.date != date || existing.minute != minute) {
                    finished = existing
                    MutableCandle(date, minute, trade.price, partial = existing == null).apply { volume += trade.volume }
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

    /** 분봉의 날짜는 저장하는 날이 아니라 그 분봉이 시작된 날이다. */
    private fun MutableCandle.toCandle() = MinuteCandle(date, minute, open, high, low, close, volume)

    private class MutableCandle(val date: LocalDate, val minute: LocalTime, price: BigDecimal, val partial: Boolean) {
        val open: BigDecimal = price
        var high: BigDecimal = price
        var low: BigDecimal = price
        var close: BigDecimal = price
        var volume: Long = 0
    }
}

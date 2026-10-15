package com.quantlog.futures

import com.quantlog.gatewayclient.RealtimeRawReceived
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime

/** 게이트웨이가 넘긴 시세 원문 중 야간선물 체결(`H0MFCNT0`)을 1분봉으로 쌓는다. 틱은 따로 저장하지 않는다. */
@Component
class NightFuturesRecorder(
    private val repository: NightFuturesCandleRepository,
    private val clock: Clock,
) {
    @EventListener
    @Transactional
    fun onRaw(event: RealtimeRawReceived) {
        if (!event.raw.contains("|${NightFuturesTickParser.TR_ID}|")) return
        NightFuturesTickParser.parse(event.raw).forEach(::record)
    }

    private fun record(tick: NightFuturesTick) {
        val minute = minuteOf(tick)
        val candle = repository.findByCodeAndMinuteStart(tick.code, minute)
        if (candle == null) {
            repository.save(NightFuturesCandle(tick.code, minute, tick.price, tick.price, tick.price, tick.price, tick.quantity))
        } else {
            candle.add(tick.price, tick.quantity)
        }
    }

    /** 체결 시각(HHmmss)에 날짜를 붙인다. 자정을 막 넘겨 받은 직전 시각의 체결은 전날로 본다. */
    private fun minuteOf(tick: NightFuturesTick): LocalDateTime {
        val now = LocalDateTime.now(clock)
        val date = if (tick.time.isAfter(now.toLocalTime().plusHours(1))) now.toLocalDate().minusDays(1) else now.toLocalDate()
        return date.atTime(tick.time.hour, tick.time.minute)
    }

    /** 가장 최근 봉 [limit] 개를 과거 → 최근 순서로. */
    @Transactional(readOnly = true)
    fun recent(code: String): List<Candle> = repository.findTop300ByCodeOrderByMinuteStartDesc(code).reversed().map { it.toCandle() }
}

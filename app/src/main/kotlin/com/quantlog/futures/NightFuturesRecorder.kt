package com.quantlog.futures

import com.quantlog.gatewayclient.RealtimeRawReceived
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime
import java.time.LocalTime

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

    private fun minuteOf(tick: NightFuturesTick): LocalDateTime = minuteOf(tick.time, LocalDateTime.now(clock))

    /** 가장 최근 봉 [limit] 개를 과거 → 최근 순서로. */
    @Transactional(readOnly = true)
    fun recent(code: String): List<Candle> = repository.findTop300ByCodeOrderByMinuteStartDesc(code).reversed().map { it.toCandle() }

    companion object {
        /**
         * 체결 시각(HHmmss)에 날짜를 붙인다. 지금보다 1시간 넘게 앞선 시각이면 자정 전 체결을 막 받은 것이라 전날로 본다.
         * 시각끼리(LocalTime)만 비교하면 23시대에 now+1시간이 00시대로 넘어가 23시 이후 체결이 전부 전날 봉에 섞이므로, 날짜까지 붙여 비교한다.
         */
        fun minuteOf(
            time: LocalTime,
            now: LocalDateTime,
        ): LocalDateTime {
            val today = now.toLocalDate().atTime(time.hour, time.minute)
            return if (today.isAfter(now.plusHours(1))) today.minusDays(1) else today
        }
    }
}

package com.quantlog.broker

import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** 거래 시장. 지금은 국내(KR)만 지원한다(2026-10-08 해외 주식 기능 제거). */
enum class Market(val currency: String) {
    KR("KRW"),
    ;

    /** 거래소 현지 시간대. */
    val zone: ZoneId get() = SEOUL

    /**
     * 정규장 중인지. 국내 09:00~15:20(15:20 이후는 종가 단일가라 제외).
     * 공휴일은 모른다 — 휴장일엔 증권사가 주문을 거부한다.
     */
    fun isRegularSession(at: ZonedDateTime): Boolean {
        val local = at.withZoneSameInstant(SEOUL)
        if (local.dayOfWeek == DayOfWeek.SATURDAY || local.dayOfWeek == DayOfWeek.SUNDAY) return false
        val time = local.toLocalTime()
        return !time.isBefore(KR_OPEN) && time.isBefore(KR_CLOSE)
    }

    /** 주문이 실제로 가능한 시간. 국내는 정규장과 같다. */
    fun isTradable(at: ZonedDateTime): Boolean = isRegularSession(at)

    private companion object {
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
        val KR_OPEN: LocalTime = LocalTime.of(9, 0)
        val KR_CLOSE: LocalTime = LocalTime.of(15, 20)
    }
}

enum class Side { BUY, SELL }

package com.quantlog.gateway.broker

import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 게이트웨이가 다루는 시장. 지금은 국내(KR)만 지원한다(2026-10-08 해외 주식 기능 제거).
 * 분봉을 모으는 시간을 가리는 데만 장 시간을 쓴다 — 주문 가능 여부 판단은 앱이 한다(해석 금지, docs/서버-분리.md).
 */
enum class Market {
    KR,
    ;

    /** 정규장 중인지. 국내 09:00~15:20(15:20 이후는 종가 단일가라 제외). 공휴일은 모른다. */
    fun isTradable(at: ZonedDateTime): Boolean {
        val local = at.withZoneSameInstant(SEOUL)
        if (local.dayOfWeek == DayOfWeek.SATURDAY || local.dayOfWeek == DayOfWeek.SUNDAY) return false
        val time = local.toLocalTime()
        return !time.isBefore(KR_OPEN) && time.isBefore(KR_CLOSE)
    }

    private companion object {
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
        val KR_OPEN: LocalTime = LocalTime.of(9, 0)
        val KR_CLOSE: LocalTime = LocalTime.of(15, 20)
    }
}

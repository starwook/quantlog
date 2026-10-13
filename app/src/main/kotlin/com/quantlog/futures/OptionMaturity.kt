package com.quantlog.futures

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

/** 코스피200 옵션 월물 만기. 만기일은 매월 둘째 목요일이다(휴장일 보정은 하지 않는다). */
object OptionMaturity {
    fun expiry(month: YearMonth): LocalDate = month.atDay(1).with(TemporalAdjusters.dayOfWeekInMonth(2, DayOfWeek.THURSDAY))

    /** 오늘 기준 가장 가까운(만기일을 지나지 않은) 월물. */
    fun nearest(today: LocalDate): YearMonth {
        val month = YearMonth.from(today)
        return if (today.isAfter(expiry(month))) month.plusMonths(1) else month
    }
}

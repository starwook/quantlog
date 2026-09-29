package com.quantlog.broker

import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

enum class Market(val currency: String) {
    KR("KRW"),
    NASDAQ("USD"),
    NYSE("USD"),
    AMEX("USD"),
    ;

    val isOverseas: Boolean get() = this != KR

    /** 거래소 현지 시간대. 미국은 뉴욕(서머타임 자동 반영). KIS 분봉의 날짜·시각도 이 기준이다. */
    val zone: ZoneId get() = if (isOverseas) NEW_YORK else SEOUL

    /**
     * 미국 주식 호가 단위: $1 이상 $0.01, 미만 $0.0001 (Reg NMS Rule 612).
     * 국내는 종목(주식/ETF)·가격대마다 달라서 여기서 정하지 않고 증권사 시세 응답의 호가 단위를 쓴다.
     */
    fun overseasTickSize(price: BigDecimal): BigDecimal {
        check(isOverseas) { "국내 호가 단위는 증권사 시세에서 받는다" }
        return if (price >= BigDecimal.ONE) BigDecimal("0.01") else BigDecimal("0.0001")
    }

    /**
     * 정규장 중인지. 국내 09:00~15:20(15:20 이후는 종가 단일가라 제외), 미국 뉴욕시간 09:30~16:00
     * (서머타임은 뉴욕 시간대로 계산해서 자동 반영). 공휴일은 모른다 — 휴장일엔 증권사가 주문을 거부한다.
     */
    fun isRegularSession(at: ZonedDateTime): Boolean {
        val local = at.withZoneSameInstant(if (isOverseas) NEW_YORK else SEOUL)
        if (local.dayOfWeek == DayOfWeek.SATURDAY || local.dayOfWeek == DayOfWeek.SUNDAY) return false
        val (open, close) = if (isOverseas) US_OPEN to US_CLOSE else KR_OPEN to KR_CLOSE
        val time = local.toLocalTime()
        return !time.isBefore(open) && time.isBefore(close)
    }

    /**
     * 주문이 실제로 가능한 시간. 국내는 정규장과 같다. 미국은 정규장뿐 아니라 프리마켓·애프터마켓도
     * 같은 주문 API로 된다고 KIS 포털에 명시돼 있다(docs/kis-api/README.md, 뉴욕시간 04:00~20:00 —
     * 서머타임은 NEW_YORK 존 변환으로 자동 반영돼서 자정을 안 넘는다).
     */
    fun isTradable(at: ZonedDateTime): Boolean {
        if (!isOverseas) return isRegularSession(at)
        val local = at.withZoneSameInstant(NEW_YORK)
        if (local.dayOfWeek == DayOfWeek.SATURDAY || local.dayOfWeek == DayOfWeek.SUNDAY) return false
        val time = local.toLocalTime()
        return !time.isBefore(US_PREMARKET_OPEN) && time.isBefore(US_AFTERHOURS_CLOSE)
    }

    private companion object {
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
        val NEW_YORK: ZoneId = ZoneId.of("America/New_York")
        val KR_OPEN: LocalTime = LocalTime.of(9, 0)
        val KR_CLOSE: LocalTime = LocalTime.of(15, 20)
        val US_OPEN: LocalTime = LocalTime.of(9, 30)
        val US_CLOSE: LocalTime = LocalTime.of(16, 0)
        val US_PREMARKET_OPEN: LocalTime = LocalTime.of(4, 0)
        val US_AFTERHOURS_CLOSE: LocalTime = LocalTime.of(20, 0)
    }
}

enum class Side { BUY, SELL }

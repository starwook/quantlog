package com.quantlog.broker

import java.math.BigDecimal
import java.time.LocalTime

interface BrokerClient {
    /** 현재가와 그 가격대의 호가 단위. */
    fun quote(
        market: Market,
        symbol: String,
    ): Quote

    /**
     * 당일 분봉 조회. 국내·해외 모두 지원(해외는 HHDFS76950200, 날짜·시각은 거래소 현지 기준).
     * KIS 쪽 제약(docs/kis-api/README.md, 2026-09-29 실측): 한 번에 최대 30건, **당일 데이터만** 제공.
     * 최신 분봉이 리스트 맨 앞에 온다(내림차순).
     */
    fun minuteCandles(
        market: Market,
        symbol: String,
        atTime: LocalTime,
    ): List<MinuteCandle>

    /**
     * 실제 체결가(평균). 국내(inquire-daily-ccld)·해외(inquire-ccnl) 모두 조회. 못 구하면 null.
     * 아직 체결 전이거나 조회 시점에 반영 안 됐으면 null — placeOrder 직후 바로 호출하면 없을 수 있으니
     * 호출 전 약간 기다린다 (2026-09-29 실측: 삼성전자 273,000원 지정가 매수 → 실제 체결 272,000원,
     * 지정가와 체결가가 다를 수 있다는 걸 실제로 확인함).
     */
    fun filledPrice(
        market: Market,
        orderNo: String,
    ): BigDecimal?

    fun buyingPower(
        market: Market,
        symbol: String,
        price: BigDecimal,
    ): BuyingPower

    /** 해외는 미국 전체(NASDAQ/NYSE/AMEX)를 한 번에 조회한다. 수량 0인 종목은 제외한다. */
    fun holdings(market: Market): List<Holding>

    fun placeOrder(order: OrderRequest): OrderReceipt
}

package com.quantlog.broker

import java.math.BigDecimal

/** 실시간 시세 한 건. 증권사별 실시간 클라이언트가 틱마다 Spring 이벤트로 발행한다. */
data class PriceTick(val market: Market, val symbol: String, val price: BigDecimal)

/**
 * 실시간(WebSocket) 시세를 받는 쪽의 공통 창구. 국내 구현체가 있다.
 * 틱은 [PriceTick] 이벤트로 나가고, 이 인터페이스는 "이 종목은 지금 실시간으로 보고 있는가"만 알려준다 —
 * 폴링 쪽이 실시간이 커버하는 종목을 건너뛰고, 연결이 끊기면 다시 맡게 하려는 용도다.
 */
interface RealtimePriceFeed {
    fun isLive(
        market: Market,
        symbol: String,
    ): Boolean
}

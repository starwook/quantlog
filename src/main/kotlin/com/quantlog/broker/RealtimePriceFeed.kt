package com.quantlog.broker

import java.math.BigDecimal

/** 실시간 시세 한 건. 증권사별 실시간 클라이언트가 틱마다 Spring 이벤트로 발행한다. */
data class PriceTick(val market: Market, val symbol: String, val price: BigDecimal)

/**
 * 실시간(WebSocket) 시세를 받는 쪽의 공통 창구. 시장별로 구현체가 따로 있을 수 있다(국내 O, 해외는 아직 폴링).
 * 틱은 [PriceTick] 이벤트로 나가고, 이 인터페이스는 "이 종목은 지금 실시간으로 보고 있는가"만 알려준다 —
 * 폴링 쪽이 실시간이 커버하는 종목을 건너뛰고, 연결이 끊기면 다시 맡게 하려는 용도다.
 * 해외 실시간을 붙일 땐 이 인터페이스 구현체를 하나 더 만들어 [PriceTick] 을 발행하면 나머지 코드는 그대로다.
 */
interface RealtimePriceFeed {
    fun isLive(
        market: Market,
        symbol: String,
    ): Boolean
}

/**
 * 실시간으로 구독하고 싶은 종목 목록의 출처. 우선순위 순서로 돌려준다(구독 상한을 넘으면 뒤쪽이 잘려 폴링으로 남는다).
 * 무엇을 구독할지는 도메인(보유·매매 설정)이 알고, 증권사 실시간 클라이언트는 이 목록만 따른다.
 */
interface RealtimeSymbolSource {
    fun symbols(market: Market): List<String>
}

package com.quantlog.gateway

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "quantlog.gateway")
data class GatewayProperties(
    /** 앱이 HTTP·웹소켓 호출에 붙이는 공유 토큰(`X-Gateway-Token`). 비우면 검사하지 않는다(사설망·로컬 개발용). */
    val token: String = "",
    /** 단일 실행 잠금 유효 시간. 이 안에 갱신하지 못하면(크래시) 다른 인스턴스가 이어받을 수 있다. */
    val leaseSeconds: Long = 30,
    val heartbeatMillis: Long = 5_000,
    /** 구독 종목 분봉을 REST 로 받아 DB 에 쌓는 주기(예전엔 앱의 진입 스케줄러가 매초 했다). */
    val candleCollection: Toggle = Toggle(enabled = true, intervalMillis = 1_000),
    /** KIS 잔고를 받아 DB 에 그대로 기록하는 주기. */
    val balance: Toggle = Toggle(enabled = true, intervalMillis = 10_000),
) {
    data class Toggle(val enabled: Boolean, val intervalMillis: Long)
}

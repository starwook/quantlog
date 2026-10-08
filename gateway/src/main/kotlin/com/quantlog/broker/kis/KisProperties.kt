package com.quantlog.broker.kis

import org.springframework.boot.context.properties.ConfigurationProperties

/** 한투 모의투자 설정. appKey/appSecret/account 는 환경변수로만 주입한다 (application.yml 참조). */
@ConfigurationProperties(prefix = "kis.mock")
data class KisProperties(
    val baseUrl: String = "https://openapivts.koreainvestment.com:29443",
    val appKey: String = "",
    val appSecret: String = "",
    /** 모의계좌 앞 8자리. "12345678-01" 형태로 넣어도 된다. */
    val account: String = "",
    val accountProduct: String = "01",
    /**
     * 모의투자 서버 호출 빈도 제한 대비 최소 호출 간격.
     * 문서상 ACCOUNT 그룹은 초당 최대 1회로 가장 엄격하다(docs/kis-api/README.md).
     * 600ms로는 "초당 거래건수를 초과하였습니다"(EGW00201)가 실제로 발생해 1100ms로 올렸다 (2026-09-28 실측).
     */
    val minIntervalMillis: Long = 1100,
    /** true 면 KIS 응답 원문을 로그로 남긴다 (첫 연동 시 응답 필드 확인용). */
    val logRaw: Boolean = false,
    /** 모의투자 실시간 시세 WebSocket. 경로(/tryitout/H0STCNT0)는 공식 예제 기준 — 첫 연결 실패하면 로그로 확인 후 고친다. */
    val wsUrl: String = "ws://ops.koreainvestment.com:31000/tryitout/H0STCNT0",
    /** 실시간 동시 구독 종목 수 상한(KIS 세션당 제한). 넘는 종목은 REST 폴링으로 남는다. */
    val realtimeMaxSubscriptions: Int = 40,
    /** KIS Developers 고객(HTS) ID. 실시간 체결통보 구독의 tr_key 라서 비우면 체결통보를 구독하지 않는다. 시크릿 취급 — application-local.yml 에만 적는다. */
    val htsId: String = "",
) {
    val accountNumber: String get() = account.substringBefore("-").trim()
    val accountProductCode: String get() =
        if (account.contains("-")) account.substringAfter("-").trim() else accountProduct

    val hasCredentials: Boolean get() = appKey.isNotBlank() && appSecret.isNotBlank() && accountNumber.isNotBlank()

    fun requireCredentials() {
        check(hasCredentials) {
            "KIS 모의투자 설정이 비어 있습니다. 환경변수 KIS_MOCK_APP_KEY / KIS_MOCK_APP_SECRET / KIS_MOCK_ACCOUNT 를 설정하세요."
        }
    }
}

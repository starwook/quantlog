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
) {
    val accountNumber: String get() = account.substringBefore("-").trim()
    val accountProductCode: String get() =
        if (account.contains("-")) account.substringAfter("-").trim() else accountProduct

    fun requireCredentials() {
        check(appKey.isNotBlank() && appSecret.isNotBlank() && accountNumber.isNotBlank()) {
            "KIS 모의투자 설정이 비어 있습니다. 환경변수 KIS_MOCK_APP_KEY / KIS_MOCK_APP_SECRET / KIS_MOCK_ACCOUNT 를 설정하세요."
        }
    }
}

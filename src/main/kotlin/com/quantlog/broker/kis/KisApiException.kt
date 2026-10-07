package com.quantlog.broker.kis

/** [code] 는 KIS 응답의 `msg_cd`. HTTP 오류·빈 응답처럼 코드가 없는 실패는 null. */
class KisApiException(message: String, cause: Throwable? = null, val code: String? = null) : RuntimeException(message, cause) {
    /** "모의투자 장시작전 입니다" — 모의투자는 프리마켓에서도 해외 주문을 거부한다(2026-10-07 실측, docs/kis-api/README.md). */
    val isMarketNotOpen: Boolean get() = code == MARKET_NOT_OPEN

    private companion object {
        const val MARKET_NOT_OPEN = "40570000"
    }
}

package com.quantlog.watchlist

import com.quantlog.broker.Market

/**
 * DB(`symbol_strategy`)에 감시 종목 행이 아직 없을 때 채워 넣는 초기값. 런타임에는 아무도 이걸 읽지 않는다 — 감시 종목의 정본은
 * DB 이고, 종목을 추가·삭제하려면 DB 행을 바꾸면 된다(여기 있는 종목을 지워도 이미 있는 행은 그대로다).
 */
enum class SeedSymbol(
    val market: Market,
    val symbol: String,
    val displayName: String,
    /** 행을 처음 만들 때 매수·마틴게일을 켤지. 실제 값은 이후 DB 가 정본이다. */
    val tradeByDefault: Boolean = false,
) {
    SAMSUNG(Market.KR, "005930", "삼성전자"),
    SK_HYNIX(Market.KR, "000660", "SK하이닉스"),
    KODEX_SEMICONDUCTOR(Market.KR, "091160", "KODEX 반도체"),
    SOXL(Market.AMEX, "SOXL", "SOXL"),
    KODEX_KOSDAQ150_LEVERAGE(Market.KR, "233740", "KODEX 코스닥150레버리지", tradeByDefault = true),
}

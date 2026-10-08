package com.quantlog.gateway.paper

import com.quantlog.gateway.broker.Market

/** 종목이 ETF 인지 알려주는 창구. 무엇이 ETF 인지는 종목 설정(watchlist)이 알고, 모킹 체결은 제세금 계산에만 쓴다. */
fun interface EtfRegistry {
    fun isEtf(
        market: Market,
        symbol: String,
    ): Boolean
}

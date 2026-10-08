package com.quantlog.marketdata

import com.quantlog.broker.Market
import java.time.LocalTime

/**
 * 증권사에서 분봉을 받아 DB 에 쌓는 일의 계약. 매매 판단(앱)이 부르고, 실제 호출은 게이트웨이 구현체가 한다.
 * 서버가 둘로 갈라지면 게이트웨이가 스스로 주기 수집하고 앱은 이 호출을 하지 않는다 — docs/서버-분리.md.
 */
interface MinuteCandleCollector {
    /** 최근 분봉을 받아 새로 생긴 것만 저장하고, 저장한 개수를 돌려준다. */
    fun fetchAndStoreRecentMinutes(
        market: Market,
        symbol: String,
        atTime: LocalTime,
    ): List<MinuteCandleEntity>
}

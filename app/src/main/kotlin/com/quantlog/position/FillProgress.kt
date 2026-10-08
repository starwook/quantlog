package com.quantlog.position

import com.quantlog.broker.Market

/**
 * "이 주문·종목의 체결이 보유 현황(DB)에 반영됐나"를 묻는 창구. 매매 판단(앱)이 쓰고, 반영은 게이트웨이가 한다.
 * 지금은 같은 JVM 의 게이트웨이 구현체(HoldingSyncService)가 답하지만, 앱이 게이트웨이에 컴파일 의존을 갖지 않도록 계약만 여기 둔다.
 * 서버가 둘로 갈라지면 이 계약을 DB(체결 원장)로 답하는 구현으로 바꾼다 — docs/서버-분리.md.
 */
interface FillProgress {
    /** 이 종목에 잔고 동기화보다 늦게 낸 주문이 있어 보유 현황이 아직 낡았으면 true. */
    fun hasUnsyncedTrade(
        market: Market,
        symbol: String,
    ): Boolean

    /** 이 주문을 체결통보로 주문수량만큼 다 반영했는가. */
    fun isOrderFilled(orderNo: String): Boolean
}

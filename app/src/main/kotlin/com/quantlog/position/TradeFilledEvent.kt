package com.quantlog.position

import com.quantlog.broker.Market

/** 주문의 체결이 확인됐다는 알림. 잔고처럼 체결에 맞춰 바로 갱신해야 하는 쪽이 듣는다. */
data class TradeFilledEvent(
    val market: Market,
    val symbol: String,
)

/** 매매 기록이 새로 생기거나(주문 접수) 상태가 바뀌었다(체결·취소·미체결 확인)는 알림. 실시간 주문 화면이 듣는다. */
data class TradeChangedEvent(val trade: Trade)

/** 보유 종목(수량·평단)이 바뀌었다는 알림. 체결통보 반영이나 KIS 잔고 동기화 뒤에 나간다. */
object HoldingsChangedEvent

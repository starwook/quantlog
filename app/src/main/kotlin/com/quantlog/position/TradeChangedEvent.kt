package com.quantlog.position

/** 매매 기록이 새로 생기거나(주문 접수) 상태가 바뀌었다(체결·취소·미체결 확인)는 알림. 실시간 주문 화면이 듣는다. */
data class TradeChangedEvent(val trade: Trade)

/** 보유 종목(수량·평단)이 바뀌었다는 알림. 체결 반영이나 잔고 스냅샷 반영 뒤에 나간다. */
object HoldingsChangedEvent

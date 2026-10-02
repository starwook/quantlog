package com.quantlog.position

import com.quantlog.broker.Market

/** 주문의 체결이 확인됐다는 알림. 잔고처럼 체결에 맞춰 바로 갱신해야 하는 쪽이 듣는다. */
data class TradeFilledEvent(
    val market: Market,
    val symbol: String,
)

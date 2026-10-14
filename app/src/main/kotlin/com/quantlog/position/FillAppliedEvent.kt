package com.quantlog.position

import com.quantlog.broker.Market
import com.quantlog.broker.Side
import java.math.BigDecimal
import java.time.Instant

/**
 * 주문의 체결이 늘어나 보유 사본에 반영될 때 [HoldingFillApplier] 가 내는 이벤트. 이 순간의 평단을 아는 곳이 거기라서
 * 매매 기록([TradeService])과 화면에 필요한 값을 그대로 실어 보낸다. [avgCostBefore] 는 반영 직전 평단(사본에 종목이 없었으면 null).
 * 체결통보로 늘었으면 [noticeId] 는 원장 줄 ID, [filledAt] 은 게이트웨이가 통보를 받은 시각이다(앱이 반영한 시각이 아니다).
 * 통보가 빠져 체결조회로 채운 몫이면 [noticeId] 는 null, [filledAt] 은 조회로 알게 된 시각이고 [price] 는 그 몫의 평균 체결가다.
 */
data class FillAppliedEvent(
    val market: Market,
    val symbol: String,
    val side: Side,
    val orderNo: String,
    val quantity: Int,
    val price: BigDecimal,
    val avgCostBefore: BigDecimal?,
    val filledAt: Instant,
    val noticeId: Long?,
)

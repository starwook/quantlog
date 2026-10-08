package com.quantlog.position

import com.quantlog.broker.Market
import com.quantlog.broker.Side
import java.math.BigDecimal
import java.time.Instant

/**
 * 체결통보 한 건이 보유 사본에 반영될 때 [HoldingSyncService] 가 내는 이벤트. 이 순간의 평단을 아는 곳이 거기라서 원장 기록에 필요한 값을
 * 그대로 실어 보낸다. [avgCostBefore] 는 반영 직전 평단(사본에 종목이 없었으면 null).
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
)

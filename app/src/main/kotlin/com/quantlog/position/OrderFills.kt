package com.quantlog.position

import com.quantlog.broker.Market
import com.quantlog.broker.Side
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

/** 게이트웨이 원장(`kis_broker_fill`)의 체결 통보 한 줄. [id] 는 `kis_broker_fill.id`, [filledAt] 은 게이트웨이가 받은 시각이다. */
data class OrderFill(
    val id: Long,
    val market: Market,
    val symbol: String,
    val side: Side,
    val orderNo: String,
    /** 이 체결 건의 수량(누적 아님). */
    val quantity: Int,
    val price: BigDecimal,
    val filledAt: Instant,
)

/**
 * 체결 내역은 게이트웨이 원장(`kis_broker_fill`) 하나뿐이다 — 앱은 사본을 따로 두지 않고 여기서 읽는다(2026-10-08).
 * "반영된" 줄은 앱이 보유 현황에 반영을 끝낸 줄(반영 커서까지)이다. 매매 판단은 반영된 줄만 봐야 보유 현황과 어긋나지 않는다.
 */
interface OrderFills {
    /** 이 주문의 체결 줄 중 `kis_broker_fill.id` 가 [lastId] 이하인 것(반영 여부와 무관). */
    fun upTo(
        orderNo: String,
        lastId: Long,
    ): List<OrderFill>

    /** 이 주문의 체결 줄 중 앱이 반영을 끝낸 것. */
    fun projected(orderNo: String): List<OrderFill>

    /** 앱이 반영을 끝낸 체결 줄 전부. */
    fun projectedAll(): List<OrderFill>

    /** 앱이 반영을 끝낸 줄에 이 주문의 접수 통보(거부 아님)가 있는가 — 증권사가 주문을 받았다는 뜻이다. */
    fun accepted(orderNo: String): Boolean
}

/** 한 주문이 지금까지 체결된 합계. 같은 통보가 중복으로 기록됐으면 합계가 주문수량을 넘을 수 있어, 쓰는 쪽이 주문수량으로 자른다. */
data class FilledSoFar(val quantity: Int, val amount: BigDecimal) {
    val averagePrice: BigDecimal? get() = if (quantity > 0) amount.divide(BigDecimal(quantity), PRICE_SCALE, RoundingMode.HALF_UP) else null

    private companion object {
        const val PRICE_SCALE = 6
    }
}

fun List<OrderFill>.total(): FilledSoFar =
    FilledSoFar(sumOf { it.quantity }, fold(BigDecimal.ZERO) { sum, f -> sum + f.price * BigDecimal(f.quantity) })

package com.quantlog.position

import com.quantlog.broker.FillNotice
import com.quantlog.broker.Market
import com.quantlog.broker.Side
import org.springframework.context.ApplicationEventPublisher
import java.math.BigDecimal
import java.time.Instant

/**
 * 테스트용 메모리 게이트웨이 원장(`kis_broker_fill`). [record] 로 통보를 한 줄씩 쌓고, [projectedUpTo] 까지를 "앱이 반영한 줄"로 본다(기본: 전부).
 */
class InMemoryBrokerFills : OrderFills {
    private class Row(val id: Long, val notice: FillNotice, val receivedAt: Instant)

    private val rows = mutableListOf<Row>()

    var projectedUpTo: Long = Long.MAX_VALUE

    /** 통보 한 줄을 원장에 쌓고 그 줄 ID 를 돌려준다. */
    fun record(
        notice: FillNotice,
        receivedAt: Instant = Instant.EPOCH,
    ): Long {
        val id = rows.size + 1L
        rows += Row(id, notice, receivedAt)
        return id
    }

    override fun upTo(
        orderNo: String,
        lastId: Long,
    ): List<OrderFill> = rows.filter { it.id <= lastId && it.notice.orderNo == orderNo }.mapNotNull { it.toFill() }

    override fun projected(orderNo: String): List<OrderFill> = upTo(orderNo, projectedUpTo)

    override fun projectedAll(): List<OrderFill> = rows.filter { it.id <= projectedUpTo }.mapNotNull { it.toFill() }

    override fun accepted(orderNo: String): Boolean =
        rows.any { it.id <= projectedUpTo && it.notice.orderNo == orderNo && !it.notice.isFill && it.notice.refuseFlag == "0" }

    private fun Row.toFill(): OrderFill? {
        if (!notice.isFill) return null
        return OrderFill(
            id,
            Market.KR,
            notice.symbol,
            notice.side ?: return null,
            notice.orderNo,
            notice.filledQuantity?.toInt() ?: return null,
            notice.filledPrice ?: return null,
            receivedAt,
        )
    }
}

/** 원장에 통보를 쌓고 바로 보유 현황에 반영한다 — [com.quantlog.gatewayclient.FillProjector] 가 하는 일을 한 줄로. */
fun HoldingSyncService.applyRecorded(
    fills: InMemoryBrokerFills,
    notice: FillNotice,
    now: Instant,
): String? = applyFill(notice, fills.record(notice, now), now, now)

/** 이벤트를 [sink] 로 모으는 발행기. [TradeService] 를 주면 [FillAppliedEvent] 를 실제처럼 그쪽에도 넘긴다. */
fun recordingPublisher(
    sink: MutableList<Any> = mutableListOf(),
    tradeService: () -> TradeService? = { null },
) = ApplicationEventPublisher { event ->
    sink += event
    if (event is FillAppliedEvent) tradeService()?.onFillApplied(event)
}

/** 체결/접수 통보 하나를 만든다. */
fun fillNotice(
    side: Side,
    quantity: Int,
    price: String,
    orderNo: String,
    symbol: String = "005930",
    orderQuantity: Int = quantity,
    fill: Boolean = true,
) = FillNotice(
    symbol = symbol,
    orderNo = orderNo,
    originalOrderNo = "",
    sellBuyCode = if (side == Side.BUY) "02" else "01",
    filledFlag = if (fill) "2" else "1",
    acceptFlag = if (fill) "2" else "1",
    refuseFlag = "0",
    filledQuantity = if (fill) BigDecimal(quantity) else null,
    filledPrice = if (fill) BigDecimal(price) else null,
    orderQuantity = BigDecimal(orderQuantity),
    orderPrice = BigDecimal(price),
    time = "092344",
)

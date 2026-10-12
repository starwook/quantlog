package com.quantlog.gatewayclient

import com.quantlog.broker.FillNotice
import com.quantlog.broker.KisFillNoticeParser
import com.quantlog.broker.Market
import com.quantlog.position.OrderFill
import com.quantlog.position.OrderFills
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate

/**
 * [OrderFills] 를 게이트웨이 원장(`kis_broker_fill`)에서 읽는다. "반영된" 줄은 [FillProjector] 의 커서까지다.
 * 원장은 추가만 되고 바뀌지 않으므로, 한 번 읽어 해석한 줄은 메모리에 두고 새 줄만 더 읽는다(주문번호가 원문 안에 있어 DB 로 걸러 읽을 수 없다).
 */
@Component
class KisBrokerFillOrderFills(
    private val notices: KisBrokerFillRowRepository,
    private val cursors: ProjectionCursorRepository,
) : OrderFills {
    private class Parsed(val id: Long, val receivedAt: Instant, val notice: FillNotice)

    private val byOrder = HashMap<String, MutableList<Parsed>>()
    private val all = ArrayList<Parsed>()
    private var loadedUpTo = 0L

    override fun upTo(
        orderNo: String,
        lastId: Long,
        day: LocalDate,
    ): List<OrderFill> = ofOrder(orderNo, day).filter { it.id <= lastId }.mapNotNull { it.toFill() }

    override fun projected(
        orderNo: String,
        day: LocalDate,
    ): List<OrderFill> = upTo(orderNo, projectedUpTo(), day)

    override fun projectedAll(): List<OrderFill> {
        val upTo = projectedUpTo()
        return synchronized(this) {
            load()
            all.filter { it.id <= upTo }
        }.mapNotNull { it.toFill() }
    }

    override fun accepted(
        orderNo: String,
        day: LocalDate,
    ): Boolean {
        val upTo = projectedUpTo()
        return ofOrder(orderNo, day).any {
            it.id <= upTo && !it.notice.isFill && (it.notice.refuseFlag.isBlank() || it.notice.refuseFlag == NOT_REFUSED)
        }
    }

    @Synchronized
    private fun ofOrder(
        orderNo: String,
        day: LocalDate,
    ): List<Parsed> {
        load()
        return byOrder[orderNo].orEmpty().filter { OrderFills.dayOf(it.receivedAt) == day }
    }

    /** 아직 안 읽은 원장 줄을 읽어 해석해 둔다. 해석할 수 없는 줄은 건너뛴다([FillProjector] 가 ERROR 로 알린다). */
    private fun load() {
        while (true) {
            val rows = notices.findTop100ByIdGreaterThanOrderByIdAsc(loadedUpTo)
            if (rows.isEmpty()) return
            rows.forEach { row ->
                KisFillNoticeParser.parse(row.trId, row.body)?.let { notice ->
                    val parsed = Parsed(row.id!!, row.receivedAt, notice)
                    all += parsed
                    byOrder.getOrPut(notice.orderNo) { mutableListOf() } += parsed
                }
                loadedUpTo = row.id!!
            }
        }
    }

    /** 지금까지 반영을 끝낸 마지막 원장 ID. 커서가 아직 없으면 0. */
    private fun projectedUpTo(): Long = cursors.findById(FILL_CURSOR).map { it.lastId }.orElse(0)

    /** 체결 통보만 [OrderFill] 로 바꾼다. 접수 통보이거나 값이 빠진 줄은 null. */
    private fun Parsed.toFill(): OrderFill? {
        if (!notice.isFill) return null
        val side = notice.side ?: return null
        val quantity = notice.filledQuantity?.toInt()?.takeIf { it > 0 } ?: return null
        val price = notice.filledPrice ?: return null
        return OrderFill(id, Market.KR, notice.symbol, side, notice.orderNo, quantity, price, receivedAt)
    }

    private companion object {
        /** RFUS_YN: 정상 접수면 "0". */
        const val NOT_REFUSED = "0"
    }
}

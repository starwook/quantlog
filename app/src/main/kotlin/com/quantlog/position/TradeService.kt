package com.quantlog.position

import com.quantlog.broker.FillNotice
import com.quantlog.broker.Market
import com.quantlog.broker.OrderReceipt
import com.quantlog.broker.OrderRequest
import com.quantlog.broker.OrderStatus
import com.quantlog.broker.Side
import com.quantlog.notification.Notifier
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.math.BigDecimal

/** 주문이 브로커에 접수될 때마다 기록을 남긴다. 봇이든 점검용 실행기든 주문을 내는 곳은 모두 이걸 거친다. */
@Service
class TradeService(
    private val repository: TradeRepository,
    private val orderFills: OrderFills,
    private val notifier: Notifier,
    private val events: ApplicationEventPublisher,
) {
    /** 매매 기록이 저장되기 전에 반영된 매도 체결의 체결 직전 평단(주문번호 → 평단). [record] 가 꺼내 쓴다. 재시작하면 사라진다(그 매도는 손익 "모름"). */
    private val sellAvgCostBeforeRecord = boundedMap<BigDecimal>()

    /** 이 종목의 체결된 매매 기록(체결 시각 오름차순). 취소·미체결 주문은 뺀다. 마틴게일 사이클 계산에 쓴다. */
    fun trades(
        market: Market,
        symbol: String,
    ): List<Trade> =
        repository.findAllByMarketAndSymbolOrderByExecutedAtAsc(
            market,
            symbol,
        ).filter { !it.canceled && it.filledPrice != null }

    /** 주문번호로 찾은 매매 기록. 주문 취소처럼 주문 응답 직후의 흐름이 쓴다. */
    fun findByOrderNo(
        market: Market,
        orderNo: String,
    ): Trade? = repository.findFirstByMarketAndOrderNo(market, orderNo)

    /**
     * 체결 한 줄이 보유 현황에 반영되면([HoldingSyncService.applyFill] 과 같은 트랜잭션) 그 주문의 매매 기록을 따라 갱신한다.
     * - 체결가·체결수량: 원장(`broker_fill`)에서 이 줄까지의 주문별 누적으로 채운다(부분체결은 누적해서 반영 — 2026-10-08).
     * - 매도면 체결 직전 평단을 적어 둔다([Trade.avgCostBefore], 손익 기준). 매도는 평단을 바꾸지 않으므로 첫 체결의 값 하나면 된다.
     * 매매 기록이 아직 없으면(주문 응답보다 체결이 먼저 반영됨) [record] 가 저장할 때 원장에서 채운다. 평단은 그때 알 수 없어 잠깐 들고 있는다.
     */
    @EventListener
    fun onFillApplied(event: FillAppliedEvent) {
        val trade = repository.findFirstByMarketAndOrderNo(event.market, event.orderNo)
        if (trade == null) {
            if (event.side == Side.SELL && event.avgCostBefore != null) sellAvgCostBeforeRecord[event.orderNo] = event.avgCostBefore
            return
        }
        var changed = event.side == Side.SELL && event.avgCostBefore != null && trade.recordAvgCostBefore(event.avgCostBefore)
        val total = orderFills.upTo(event.orderNo, event.brokerFillId).total()
        total.averagePrice?.let { if (trade.applyFill(it, total.quantity)) changed = true }
        if (changed) {
            repository.save(trade)
            events.publishEvent(TradeChangedEvent(trade))
        }
    }

    /** 원장의 접수 통보(체결 아님): 증권사가 주문을 받았다 — 아직 체결 전이면 "미체결(증권사 확인)"으로 표시한다. 거부 통보는 건너뛴다. */
    fun onOrderNotice(notice: FillNotice) {
        if (notice.isFill || (notice.refuseFlag.isNotBlank() && notice.refuseFlag != NOT_REFUSED)) return
        val trade = repository.findFirstByMarketAndOrderNo(Market.KR, notice.orderNo) ?: return
        if (trade.apply(OrderStatus.Open)) {
            repository.save(trade)
            events.publishEvent(TradeChangedEvent(trade))
        }
    }

    fun find(id: Long): Trade = repository.findById(id).orElseThrow { IllegalStateException("주문 기록이 없습니다: $id") }

    /** 증권사 조회 결과를 이 주문에 반영해 저장한다. */
    fun applyStatus(
        trade: Trade,
        status: OrderStatus,
    ) {
        val dbBefore = trade.fillStateText()
        if (!trade.apply(status)) return
        if (status is OrderStatus.Filled) reportFillCorrection(trade, dbBefore, status)
        repository.save(trade)
        events.publishEvent(TradeChangedEvent(trade))
    }

    fun markCanceled(trade: Trade) {
        trade.cancel()
        repository.save(trade)
        events.publishEvent(TradeChangedEvent(trade))
    }

    /**
     * 주문 응답 직후 매매 기록을 저장한다. 그사이 원장에 이미 반영된 체결·접수 통보가 있으면 그것으로 체결가·수량·미체결 확인을 채운다.
     * filledPrice: 호출부가 따로 조회한 실제 체결가(점검용 실행기만 쓴다). 못 구했으면 null로 둔다 — 지어내지 않는다.
     */
    fun record(
        order: OrderRequest,
        receipt: OrderReceipt,
        reason: String,
        filledPrice: BigDecimal? = null,
        openConfirmed: Boolean = false,
    ): Trade {
        val noticed = if (filledPrice == null) orderFills.projected(receipt.orderNo).total() else null
        val knownFilledPrice = filledPrice ?: noticed?.averagePrice
        // 호출부가 체결가를 넘겼으면 전량 체결로 본다. 통보에서 온 값이면 지금까지 체결된 수량만큼만이다.
        val knownFilledQuantity =
            if (filledPrice != null) {
                order.quantity
            } else {
                noticed?.quantity?.takeIf {
                    it > 0
                }?.let { minOf(it, order.quantity) }
            }
        val trade =
            repository.save(
                Trade(
                    market = order.market,
                    symbol = order.symbol,
                    side = order.side,
                    quantity = order.quantity,
                    orderPrice = order.limitPrice,
                    orderNo = receipt.orderNo,
                    message = receipt.message,
                    branchNo = receipt.branchNo.ifBlank { null },
                    reason = reason,
                    initialFilledPrice = knownFilledPrice,
                    initialOpenConfirmed = openConfirmed || (knownFilledPrice == null && orderFills.accepted(receipt.orderNo)),
                    initialFilledQuantity = knownFilledQuantity,
                    initialAvgCostBefore = sellAvgCostBeforeRecord.remove(receipt.orderNo).takeIf { order.side == Side.SELL },
                ),
            )
        notifier.send(
            "${if (order.side == Side.BUY) "🟢 매수" else "🔴 매도"} ${order.market} ${order.symbol} x${order.quantity} " +
                "@ ${knownFilledPrice ?: order.limitPrice}${if (knownFilledPrice == null) " (지정가)" else ""} " +
                "주문번호=${receipt.orderNo}\n사유: $reason",
        )
        events.publishEvent(TradeChangedEvent(trade))
        return trade
    }

    private companion object {
        /** RFUS_YN: 정상 접수면 "0". */
        const val NOT_REFUSED = "0"
    }
}

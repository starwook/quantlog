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
    private val tradeFills: TradeFillRepository,
    private val notifier: Notifier,
    private val events: ApplicationEventPublisher,
) {
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
     * 국내 체결 통보가 오면, 이미 저장된 매매 기록이 있으면 원장([TradeFill])의 주문별 누적으로 체결가·체결수량을 채운다. 기록이 아직 없으면 [record] 가 저장할 때 원장에서 구해 채운다.
     * [TradeFilledEvent] 는 일부러 내지 않는다 — 그 이벤트는 REST 잔고 동기화를 돌리는데, 이 메서드는 WebSocket 수신 스레드에서 불리므로
     * 수 초 걸리는 REST 호출로 틱 수신을 막으면 안 된다(보유 수량은 [HoldingSyncService.applyFill] 이 이미 반영한다).
     */
    @EventListener
    fun onFillNotice(notice: FillNotice) {
        if (!notice.isFill) return
        // 누적은 메모리가 아니라 원장에서 구한다 — 이 통보는 호출 직전 [HoldingSyncService.applyFill] 이 같은 트랜잭션에서 원장에 적었다.
        val total = tradeFills.filledSoFar(Market.KR, notice.orderNo)
        val average = total.averagePrice ?: return
        val cumulative = total.quantity
        val trade = repository.findFirstByMarketAndOrderNo(Market.KR, notice.orderNo) ?: return
        // 통보는 건별 체결이라 누적해서 반영한다 — 첫 통보로 "전량 체결"이 되면 부분체결 주문이 체결로 보인다(2026-10-08).
        if (trade.applyFill(average, cumulative)) {
            repository.save(trade)
            events.publishEvent(TradeChangedEvent(trade))
        }
    }

    fun find(id: Long): Trade = repository.findById(id).orElseThrow { IllegalStateException("주문 기록이 없습니다: $id") }

    /** 증권사 조회 결과를 이 주문에 반영해 저장한다. 체결로 바뀌면 잔고 동기화가 돌도록 알린다. */
    fun applyStatus(
        trade: Trade,
        status: OrderStatus,
    ) {
        val dbBefore = trade.fillStateText()
        if (!trade.apply(status)) return
        if (status is OrderStatus.Filled) reportFillCorrection(trade, dbBefore, status)
        repository.save(trade)
        events.publishEvent(TradeChangedEvent(trade))
        if (status is OrderStatus.Filled) events.publishEvent(TradeFilledEvent(trade.market, trade.symbol))
    }

    fun markCanceled(trade: Trade) {
        trade.cancel()
        repository.save(trade)
        events.publishEvent(TradeChangedEvent(trade))
    }

    /** filledPrice: 실제 체결가(호출부가 조회해서 넘긴다). 못 구했으면 null로 둔다 — 지어내지 않는다. */
    fun record(
        order: OrderRequest,
        receipt: OrderReceipt,
        reason: String,
        filledPrice: BigDecimal? = null,
        openConfirmed: Boolean = false,
    ): Trade {
        val noticed = if (filledPrice == null) tradeFills.filledSoFar(order.market, receipt.orderNo) else null
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
                    initialOpenConfirmed = openConfirmed,
                    initialFilledQuantity = knownFilledQuantity,
                ),
            )
        notifier.send(
            "${if (order.side == Side.BUY) "🟢 매수" else "🔴 매도"} ${order.market} ${order.symbol} x${order.quantity} " +
                "@ ${knownFilledPrice ?: order.limitPrice}${if (knownFilledPrice == null) " (지정가)" else ""} " +
                "주문번호=${receipt.orderNo}\n사유: $reason",
        )
        events.publishEvent(TradeChangedEvent(trade))
        if (knownFilledPrice != null) events.publishEvent(TradeFilledEvent(order.market, order.symbol))
        return trade
    }
}

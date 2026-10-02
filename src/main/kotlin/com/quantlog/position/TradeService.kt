package com.quantlog.position

import com.quantlog.broker.Market
import com.quantlog.broker.OrderReceipt
import com.quantlog.broker.OrderRequest
import com.quantlog.broker.Side
import com.quantlog.notification.Notifier
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import java.math.BigDecimal

/** 주문이 브로커에 접수될 때마다 기록을 남긴다. 봇이든 점검용 실행기든 주문을 내는 곳은 모두 이걸 거친다. */
@Service
class TradeService(
    private val repository: TradeRepository,
    private val notifier: Notifier,
    private val events: ApplicationEventPublisher,
) {
    /** 이 종목의 매매 기록(체결 시각 오름차순). 마틴게일 사이클 계산에 쓴다. */
    fun trades(
        market: Market,
        symbol: String,
    ): List<Trade> = repository.findAllByMarketAndSymbolOrderByExecutedAtAsc(market, symbol)

    /** filledPrice: 실제 체결가(호출부가 조회해서 넘긴다). 못 구했으면 null로 둔다 — 지어내지 않는다. */
    fun record(
        order: OrderRequest,
        receipt: OrderReceipt,
        reason: String,
        filledPrice: BigDecimal? = null,
    ): Trade {
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
                    reason = reason,
                    initialFilledPrice = filledPrice,
                ),
            )
        notifier.send(
            "${if (order.side == Side.BUY) "🟢 매수" else "🔴 매도"} ${order.market} ${order.symbol} x${order.quantity} " +
                "@ ${filledPrice ?: order.limitPrice}${if (filledPrice == null) " (지정가)" else ""} " +
                "주문번호=${receipt.orderNo}\n사유: $reason",
        )
        if (filledPrice != null) events.publishEvent(TradeFilledEvent(order.market, order.symbol))
        return trade
    }
}

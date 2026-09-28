package com.quantlog.position

import com.quantlog.broker.OrderReceipt
import com.quantlog.broker.OrderRequest
import org.springframework.stereotype.Service

/** 주문이 브로커에 접수될 때마다 기록을 남긴다. 봇이든 점검용 실행기든 주문을 내는 곳은 모두 이걸 거친다. */
@Service
class TradeService(private val repository: TradeRepository) {
    fun record(
        order: OrderRequest,
        receipt: OrderReceipt,
        reason: String,
    ): Trade =
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
            ),
        )
}

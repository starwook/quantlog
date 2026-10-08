package com.quantlog.position

import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/** [FillAppliedEvent] 를 원장([TradeFill])에 저장한다. 보유 사본 변경과 같은 트랜잭션 안에서 불려 둘이 함께 저장되거나 함께 취소된다. */
@Component
class FillLedger(private val repository: TradeFillRepository) {
    @EventListener
    fun record(event: FillAppliedEvent) {
        repository.save(
            TradeFill(
                market = event.market,
                symbol = event.symbol,
                side = event.side,
                orderNo = event.orderNo,
                quantity = event.quantity,
                price = event.price,
                avgCostBefore = event.avgCostBefore,
                filledAt = event.filledAt,
            ),
        )
    }
}

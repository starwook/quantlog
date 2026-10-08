package com.quantlog.position

import com.quantlog.broker.Market
import com.quantlog.broker.Side
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
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

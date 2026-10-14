package com.quantlog.position

import com.quantlog.broker.Market
import org.springframework.stereotype.Component
import java.time.Instant

/** 주문의 체결이 보유 현황에 얼마나 반영됐는지 답한다. 스케줄러·서비스는 반영하는 쪽이 아니라 이 계약만 안다. */
@Component
class FillProgressTracker(
    private val tradeRepository: TradeRepository,
    private val fillStates: OrderFillStates,
    private val state: HoldingUpdateTimes,
) : FillProgress {
    /**
     * 이 종목의 마지막 주문의 체결통보가 아직 다 반영되지 않았으면 true — 보유 현황이 그 주문을 아직 반영하지 못한 상태라
     * 스케줄러는 이 종목 판단(추가 매수·청산)을 미룬다. 보유 현황은 체결통보로만 바뀌므로(잔고는 같은 시점일 때만 대조한다), 동기화가 지나갔는지가 아니라
     * 통보가 왔는지로 본다. 취소된 주문과 [ORDER_IN_FLIGHT_LIMIT] 이전 주문은 기다리지 않는다. 첫 동기화 전에도 true.
     */
    override fun hasUnsyncedTrade(
        market: Market,
        symbol: String,
    ): Boolean {
        val synced = state.lastSyncedAt ?: return true
        val last = tradeRepository.findFirstByMarketAndSymbolOrderByExecutedAtDesc(market, symbol) ?: return false
        // 체결을 이미 다 반영한 주문이면 동기화를 기다릴 필요가 없다.
        if (last.canceled || isOrderFilled(last.orderNo)) return false
        return last.executedAt.isAfter(synced) || last.executedAt.isAfter(Instant.now().minus(ORDER_IN_FLIGHT_LIMIT))
    }

    /**
     * 이 주문의 체결을 주문수량만큼 다 반영했는가. 마틴게일의 "단계 진행 중"이 풀리는 기준이다. 주문별 체결 합계(`order_fill`)로 구해
     * 재시작해도 같고, 보유 현황에 아직 안 들어간 체결로 대기를 먼저 풀지 않는다(보유 반영과 합계 갱신이 한 트랜잭션이다).
     */
    override fun isOrderFilled(orderNo: String): Boolean {
        val trade = tradeRepository.findByOrderNo(Market.KR, orderNo) ?: return false
        return fillStates.filled(orderNo).quantity >= trade.quantity
    }

    override fun isOrderClosed(orderNo: String): Boolean {
        val trade = tradeRepository.findByOrderNo(Market.KR, orderNo) ?: return false
        return fillStates.isClosed(orderNo, trade.quantity)
    }
}

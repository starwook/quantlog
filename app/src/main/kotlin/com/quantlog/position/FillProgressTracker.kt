package com.quantlog.position

import com.quantlog.broker.Market
import org.springframework.stereotype.Component

/** 주문의 체결이 보유 현황에 얼마나 반영됐는지 답한다. 스케줄러·서비스는 반영하는 쪽이 아니라 이 계약만 안다. */
@Component
class FillProgressTracker(
    private val tradeRepository: TradeRepository,
    private val orderFills: OrderFills,
    private val state: HoldingUpdateTimes,
) : FillProgress {
    /**
     * 이 종목에 잔고 동기화보다 늦게 낸 주문이 있으면 true — 잔고 테이블이 그 주문을 아직 반영하지 못한 상태라
     * 스케줄러는 이 종목 판단(추가 매수·청산)을 다음 동기화까지 미룬다. 첫 동기화 전에도 true.
     */
    override fun hasUnsyncedTrade(
        market: Market,
        symbol: String,
    ): Boolean {
        val synced = state.lastSyncedAt ?: return true
        val last = tradeRepository.findFirstByMarketAndSymbolOrderByExecutedAtDesc(market, symbol) ?: return false
        // 체결을 이미 다 반영한 주문이면 동기화를 기다릴 필요가 없다.
        if (isOrderFilled(last.orderNo)) return false
        return last.executedAt.isAfter(synced)
    }

    /**
     * 이 주문의 체결을 주문수량만큼 다 반영했는가. 마틴게일의 "단계 진행 중"이 풀리는 기준이다. 원장(`kis_broker_fill`) 중 앱이 반영을 끝낸 줄로 구해
     * 재시작해도 같고, 보유 현황에 아직 안 들어간 체결로 대기를 먼저 풀지 않는다.
     */
    override fun isOrderFilled(orderNo: String): Boolean {
        val trade = tradeRepository.findByOrderNo(Market.KR, orderNo) ?: return false
        return orderFills.projected(orderNo).total().quantity >= trade.quantity
    }
}

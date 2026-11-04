package com.quantlog.position

import com.quantlog.broker.Market
import org.springframework.stereotype.Component

/** 주문의 체결이 보유 현황에 얼마나 반영됐는지 답한다. 스케줄러·서비스는 반영하는 쪽이 아니라 이 계약만 안다. */
@Component
class FillProgressTracker(
    private val tradeRepository: TradeRepository,
    private val fillStates: OrderFillStates,
    private val state: HoldingUpdateTimes,
) : FillProgress {
    /**
     * 이 종목의 마지막 주문이 아직 끝났다고 확인되지 않았으면 true — 보유 현황이 그 주문을 아직 다 반영하지 못했을 수 있어
     * 매수 판단(주기 재매수·마틴게일)을 미룬다. 끝남 = 주문수량만큼 체결을 반영했거나 체결조회가 남은 수량 0(취소 포함)으로 확인했다.
     * 취소만 된 주문은 취소 전 일부 체결의 통보가 빠졌을 수 있어 끝난 게 아니다(2026-10-14).
     * **시간이 지났다고 풀지 않는다**(2026-11-04: 예전엔 3분 지난 주문은 기다리지 않아, 통보가 빠진 채 낡은 보유로 다음 주문이 나갈 수 있었다).
     * 전날 이전 주문은 끝난 것으로 본다 — 국내 지정가 주문은 장 마감으로 소멸하고, 끝남 확인은 오늘 주문만 찾는다(원칙집 "주문 후 처리" ②).
     * 첫 잔고 동기화 전에도 true.
     */
    override fun hasUnfinishedOrder(
        market: Market,
        symbol: String,
    ): Boolean {
        if (state.lastSyncedAt == null) return true
        val last = tradeRepository.findFirstByMarketAndSymbolOrderByExecutedAtDesc(market, symbol) ?: return false
        if (OrderFills.dayOf(last.executedAt) < OrderFills.today()) return false
        return !isOrderClosed(last.orderNo)
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

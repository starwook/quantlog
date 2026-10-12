package com.quantlog.position

import com.quantlog.broker.Market
import org.springframework.data.jpa.repository.JpaRepository

interface TradeRepository : JpaRepository<Trade, Long> {
    fun findAllByOrderByExecutedAtDesc(): List<Trade>

    /** 분봉 차트에 매수/매도 지점을 표기할 때 쓴다 (com.quantlog.chart). */
    fun findAllByMarketAndSymbolOrderByExecutedAtAsc(
        market: Market,
        symbol: String,
    ): List<Trade>

    /** 이 종목의 가장 최근 주문. 잔고 동기화가 그 주문을 반영했는지 판단할 때 쓴다. */
    fun findFirstByMarketAndSymbolOrderByExecutedAtDesc(
        market: Market,
        symbol: String,
    ): Trade?

    /**
     * 주문번호로 매매 기록을 찾는다(체결통보·주문 취소가 이 기록을 이어 붙일 때 쓴다). **KIS 주문번호는 날마다 다시 시작해서 옛 기록과 겹친다**
     * (2026-10-12: 10-06 주문과 번호가 같아, 오늘 체결통보가 옛 주문에 붙으려다 무시돼 오늘 주문이 "체결 확인중"에 남았다).
     * 그래서 같은 번호 중 가장 최근 것을 돌려준다 — 체결통보·취소는 항상 방금 낸 주문에 대한 것이다.
     */
    fun findFirstByMarketAndOrderNoOrderByExecutedAtDesc(
        market: Market,
        orderNo: String,
    ): Trade?
}

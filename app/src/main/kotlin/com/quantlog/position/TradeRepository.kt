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

    /** 주문번호로 매매 기록을 찾는다(체결통보·주문 취소가 이 기록을 이어 붙일 때 쓴다). 주문번호는 하루 단위로 유일하다. */
    fun findFirstByMarketAndOrderNo(
        market: Market,
        orderNo: String,
    ): Trade?
}

package com.quantlog.position

import com.quantlog.broker.Market
import org.springframework.data.jpa.repository.JpaRepository

interface TradeRepository : JpaRepository<Trade, Long> {
    fun findAllByOrderByExecutedAtDesc(): List<Trade>

    /** [TradeReconciler] 가 아직 체결가를 못 채운 기록을 다시 확인할 때 쓴다. */
    fun findAllByMarketAndFilledPriceIsNull(market: Market): List<Trade>

    /** 분봉 차트에 매수/매도 지점을 표기할 때 쓴다 (com.quantlog.chart). */
    fun findAllByMarketAndSymbolOrderByExecutedAtAsc(
        market: Market,
        symbol: String,
    ): List<Trade>
}

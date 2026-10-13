package com.quantlog.position

import com.quantlog.broker.Market
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.time.LocalDate

interface TradeRepository : JpaRepository<Trade, Long> {
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

    /** 이 종목에서 [since] 이후에 낸 주문. 잔고 동기화가 체결통보를 기다리는 주문이 있는지 볼 때 쓴다. */
    fun findAllByMarketAndSymbolAndExecutedAtAfter(
        market: Market,
        symbol: String,
        since: Instant,
    ): List<Trade>

    /** 같은 주문번호의 기록 전부(날이 다른 것 포함). 보통은 [findByOrderNo] 로 날짜까지 맞춰 찾는다. */
    fun findAllByMarketAndOrderNo(
        market: Market,
        orderNo: String,
    ): List<Trade>
}

/**
 * 주문번호로 그날(KST [day], 기본 오늘)의 매매 기록을 찾는다(체결통보·주문 취소가 이 기록을 이어 붙일 때 쓴다).
 * **KIS 주문번호는 날마다 다시 시작해서 옛 기록과 겹치므로 번호만으로 찾으면 안 된다**
 * (2026-10-12: 10-06 주문과 번호가 같아, 오늘 체결통보가 옛 주문에 붙으려다 무시돼 오늘 주문이 "체결 확인중"에 남았다).
 */
fun TradeRepository.findByOrderNo(
    market: Market,
    orderNo: String,
    day: LocalDate = OrderFills.today(),
): Trade? = findAllByMarketAndOrderNo(market, orderNo).lastOrNull { OrderFills.dayOf(it.executedAt) == day }

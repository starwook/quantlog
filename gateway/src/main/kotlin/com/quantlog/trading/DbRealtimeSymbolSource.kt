package com.quantlog.trading

import com.quantlog.broker.Market
import com.quantlog.broker.RealtimeSymbolSource
import com.quantlog.position.AccountHoldingRepository
import com.quantlog.watchlist.SymbolStrategyRepository
import org.springframework.stereotype.Component

/**
 * 실시간 구독 대상 = 지금 보유 중인 종목(DB 잔고 사본) + 관심종목. 보유 종목이 앞이라 구독 상한을 넘으면 관심종목이 먼저 잘린다.
 * 보유 종목은 청산 감시를 빠르게 하려는 것이고, 관심종목은 차트 화면을 실시간으로 보여주려는 것이다.
 * 진입 판단용 분봉은 EntryScheduler(앱) 가 REST 로 받는다. 새로 사거나 다 팔면 다음 갱신 때 구독이 따라온다.
 */
@Component
class DbRealtimeSymbolSource(
    private val accountHoldingRepository: AccountHoldingRepository,
    private val symbolStrategyRepository: SymbolStrategyRepository,
) : RealtimeSymbolSource {
    override fun symbols(market: Market): List<String> {
        val held = accountHoldingRepository.findAll().filter { it.market == market && it.quantity > 0 }.map { it.symbol }
        val watched = symbolStrategyRepository.findAll().sortedBy { it.id }.filter { it.market == market }.map { it.symbol }
        return (held + watched).distinct()
    }
}

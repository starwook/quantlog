package com.quantlog.trading

import com.quantlog.broker.Market
import com.quantlog.broker.RealtimeSymbolSource
import com.quantlog.position.AccountHoldingRepository
import org.springframework.stereotype.Component

/**
 * 실시간 구독 대상 = 지금 보유 중인 종목(DB 잔고 사본). 실시간은 청산 감시를 빠르게 하려는 것이라 보유 종목만 필요하다.
 * 진입 판단용 분봉은 [EntryScheduler] 가 REST 로 받는다. 새로 사거나 다 팔면 다음 갱신 때 구독이 따라온다.
 */
@Component
class DbRealtimeSymbolSource(
    private val accountHoldingRepository: AccountHoldingRepository,
) : RealtimeSymbolSource {
    override fun symbols(market: Market): List<String> =
        accountHoldingRepository.findAll().filter { it.market == market && it.quantity > 0 }.map { it.symbol }
}

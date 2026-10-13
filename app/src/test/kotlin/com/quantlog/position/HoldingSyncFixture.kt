package com.quantlog.position

import com.quantlog.broker.FillNotice
import com.quantlog.broker.Holding
import com.quantlog.broker.Market
import com.quantlog.watchlist.SymbolStrategyService
import org.springframework.context.ApplicationEventPublisher
import java.time.Instant

/** 보유 현황을 고치는 세 클래스([HoldingFillApplier]·[HoldingBalanceSync]·[FillProgressTracker])를 상태까지 같이 묶어 테스트에서 하나처럼 쓴다. */
class HoldingSyncFixture(
    repository: AccountHoldingRepository,
    tradeRepository: TradeRepository,
    orderFills: OrderFills,
    symbolNames: SymbolStrategyService,
    events: ApplicationEventPublisher,
) {
    private val state = HoldingUpdateTimes()
    private val fillApplier = HoldingFillApplier(repository, tradeRepository, orderFills, symbolNames, events, state)
    private val balanceSync = HoldingBalanceSync(repository, symbolNames, events, state, orderFills)
    private val progress = FillProgressTracker(tradeRepository, orderFills, state)

    fun applyFill(
        notice: FillNotice,
        noticeId: Long,
        receivedAt: Instant,
        now: Instant = Instant.now(),
    ): String? = fillApplier.applyFill(notice, noticeId, receivedAt, now)

    fun sync(
        kis: List<Holding>,
        fetchedAt: Instant = Instant.now(),
    ): List<String> = balanceSync.sync(kis, fetchedAt)

    fun hasUnsyncedTrade(
        market: Market,
        symbol: String,
    ) = progress.hasUnsyncedTrade(market, symbol)

    fun isOrderFilled(orderNo: String) = progress.isOrderFilled(orderNo)
}

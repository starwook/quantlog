package com.quantlog.position

import com.quantlog.broker.FillNotice
import com.quantlog.broker.Holding
import com.quantlog.broker.Market
import com.quantlog.watchlist.SymbolStrategyService
import org.springframework.context.ApplicationEventPublisher
import java.time.Instant

/** 보유 현황을 고치는 세 클래스와 주문별 체결 합계(`order_fill`)([HoldingFillApplier]·[HoldingBalanceSync]·[FillProgressTracker])를 상태까지 같이 묶어 테스트에서 하나처럼 쓴다. */
class HoldingSyncFixture(
    repository: AccountHoldingRepository,
    tradeRepository: TradeRepository,
    orderFills: OrderFills,
    symbolNames: SymbolStrategyService,
    events: ApplicationEventPublisher,
) {
    private val state = HoldingUpdateTimes()

    /** 주문별 체결 합계(`order_fill`). 테스트가 들여다보거나 [TradeService] 등에 같이 넘긴다. */
    val fills = InMemoryOrderFillStates()
    val fillApplier = HoldingFillApplier(repository, tradeRepository, fills.repository, orderFills, symbolNames, events, state)
    private val balanceSync = HoldingBalanceSync(repository, symbolNames, events, state, fills.states, tradeRepository)
    private val progress = FillProgressTracker(tradeRepository, fills.states, state)

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

    fun isOrderClosed(orderNo: String) = progress.isOrderClosed(orderNo)
}

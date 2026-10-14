package com.quantlog.order

import com.quantlog.broker.Market
import com.quantlog.broker.PriceTick
import com.quantlog.broker.Side
import com.quantlog.common.KST
import com.quantlog.common.money
import com.quantlog.common.pnlCss
import com.quantlog.common.signedMoneyAndPercent
import com.quantlog.position.AccountCashService
import com.quantlog.position.AccountHolding
import com.quantlog.position.AccountHoldingRepository
import com.quantlog.position.CashChangedEvent
import com.quantlog.position.FillAppliedEvent
import com.quantlog.position.HoldingsChangedEvent
import com.quantlog.position.OrderFillState
import com.quantlog.position.OrderFillStates
import com.quantlog.position.OrderFills
import com.quantlog.position.OrderState
import com.quantlog.position.PortfolioService
import com.quantlog.position.PortfolioSnapshot
import com.quantlog.position.RealizedPnl
import com.quantlog.position.Trade
import com.quantlog.position.TradeChangedEvent
import com.quantlog.position.TradeService
import com.quantlog.watchlist.SymbolStrategyService
import jakarta.annotation.PreDestroy
import mu.KotlinLogging
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.web.socket.WebSocketSession
import java.time.LocalDate
import java.util.concurrent.Executors

private val log = KotlinLogging.logger {}

/**
 * 주문이 접수·체결·취소되거나 보유 종목이 바뀔 때마다 열려 있는 모든 브라우저(/ws/portfolio)에 그 변화를 밀어준다.
 * 새로 연결되면 보유 종목·대기열·오늘 체결 내역 전부를 한 번에 보내(snapshot) 화면이 바로 채워지게 한다.
 */
@Component
class PortfolioLiveBroadcaster(
    private val portfolioService: PortfolioService,
    private val accountHoldingRepository: AccountHoldingRepository,
    private val orderFills: OrderFills,
    private val symbolStrategyService: SymbolStrategyService,
    private val sessions: LiveSessions,
    private val valuation: LiveValuation,
    private val cash: AccountCashService,
    private val fillStates: OrderFillStates,
    private val tradeService: TradeService,
) {
    // 틱 푸시 전용 스레드. 스프링 스케줄러(스레드 하나를 모든 @Scheduled 가 공유)나 KIS 수신 스레드에 얹으면 느린 호출·클라이언트에 막혀 화면이 멎는다.
    private val pushExecutor = Executors.newSingleThreadExecutor { Thread(it, "portfolio-live-push").apply { isDaemon = true } }

    /** 지금 보유 중인 국내 종목(수량·평단). 보유가 바뀌어 목록을 다시 보낼 때마다 갱신한다. */
    @Volatile
    private var heldKr: Map<String, AccountHolding> = emptyMap()

    fun connect(session: WebSocketSession) {
        sessions.add(session)
        val snapshot = portfolioService.snapshot()
        val todayStart = LocalDate.now(KST).atStartOfDay(KST).toInstant()
        val newestFirst = snapshot.trades.sortedByDescending { it.executedAt }
        // 오늘 낸 주문 전부. 체결 내역 줄이 같은 주문번호의 사유(reason)를 이 목록에서 찾아 붙이므로 오늘 체결 내역만큼 있어야 한다.
        val orders = newestFirst.filter { it.executedAt.isAfter(todayStart) }.map { it.toView(snapshot.realizedPnlByTradeId[it.id]) }
        val snapshotMessage =
            mapOf(
                "type" to "snapshot",
                "orders" to orders,
                "fills" to todaysFills(snapshot),
                "holdings" to holdingViews(),
                "summary" to summaryView(snapshot),
            )
        sessions.send(session, snapshotMessage)
    }

    fun disconnect(session: WebSocketSession) {
        sessions.remove(session)
    }

    @EventListener
    fun onTradeChanged(event: TradeChangedEvent) {
        if (sessions.isEmpty()) return
        // 주문이 나가고 취소될 때마다 미체결 매수가 잡고 있는 돈이 달라진다.
        pushOrderable()
        val trade = event.trade
        // 실현손익은 체결된 매도에만 있고 요약 전체를 다시 계산해야 해서 그때만 구한다.
        if (trade.side == Side.SELL && trade.state == OrderState.FILLED) {
            val snapshot = portfolioService.snapshot()
            broadcast(
                mapOf(
                    "type" to "order",
                    "order" to trade.toView(snapshot.realizedPnlByTradeId[trade.id]),
                    "summary" to summaryView(snapshot),
                ),
            )
        } else {
            broadcast(mapOf("type" to "order", "order" to trade.toView(null)))
        }
        // 일부 체결된 주문이 취소되면 체결 내역 줄의 상태(일부 체결 후 취소)도 바뀐다.
        if (trade.canceled) {
            orderFillRow(trade.orderNo, OrderFills.dayOf(trade.executedAt))?.let {
                broadcast(mapOf("type" to "fill", "fill" to it))
            }
        }
    }

    /**
     * 주문의 체결이 늘면(체결통보·체결조회) 체결 내역의 그 주문 줄을 새 값으로 다시 보낸다(같은 키 — 화면이 줄을 바꿔 끼운다). 매도면 오늘 손익 요약도 같이 보낸다.
     * 커밋된 뒤에 불러야 주문별 체결 합계·요약에 그 체결이 들어간다.
     */
    @TransactionalEventListener(fallbackExecution = true)
    fun onFillApplied(event: FillAppliedEvent) {
        if (sessions.isEmpty()) return
        val snapshot = portfolioService.snapshot()
        val row = orderFillRow(event.orderNo, OrderFills.dayOf(event.filledAt), snapshot) ?: return
        val message = mutableMapOf<String, Any?>("type" to "fill", "fill" to row)
        if (event.side == Side.SELL) message["summary"] = summaryView(snapshot)
        broadcast(message)
    }

    /** 현금이 바뀌면(체결 반영·증권사 잔고 보정) 주문가능금액만 다시 보낸다. 커밋된 뒤에 읽어야 해서 트랜잭션이 없으면 바로 실행한다. */
    @TransactionalEventListener(fallbackExecution = true)
    fun onCashChanged(event: CashChangedEvent) {
        if (sessions.isEmpty()) return
        pushOrderable()
    }

    private fun pushOrderable() = broadcast(mapOf("type" to "cash", "orderableText" to (cash.orderable(Market.KR)?.money(KRW) ?: "-")))

    /** 보유 종목이 바뀌면 전체 목록을 다시 보낸다(종목 수가 적다). 커밋된 뒤에 읽어야 해서 트랜잭션이 없으면 바로 실행한다. */
    @TransactionalEventListener(fallbackExecution = true)
    fun onHoldingsChanged(event: HoldingsChangedEvent) {
        if (sessions.isEmpty()) return
        broadcast(mapOf("type" to "holdings", "holdings" to holdingViews(), "summary" to summaryView(portfolioService.snapshot())))
    }

    /**
     * 보유 중인 국내 종목의 실시간 틱이 오면 그 틱마다 현재가·평가손익을 계산해 바로 보낸다. 수량·평단은 메모리([heldKr])에 있어 DB 를 읽지 않는다.
     * 틱은 KIS 수신 스레드에서 오므로, 브라우저 전송(느린 클라이언트에 막힐 수 있다)은 전용 스레드로 넘긴다.
     */
    @EventListener
    fun onPriceTick(tick: PriceTick) {
        if (tick.market != Market.KR || sessions.isEmpty() || tick.symbol !in heldKr) return
        valuation.record(tick.symbol, tick.price)
        pushExecutor.execute { runCatching { pushPrice(tick.symbol) }.onFailure { log.warn(it) { "[주문 화면] 실시간 현재가 푸시 실패" } } }
    }

    @PreDestroy
    fun stopPricePush() {
        pushExecutor.shutdownNow()
    }

    private fun pushPrice(symbol: String) {
        val holding = heldKr[symbol] ?: return
        val (amount, percent) = valuation.unrealizedOf(heldKr.values)
        broadcast(
            mapOf(
                "type" to "price",
                "holding" to
                    HoldingLiveView.of(holding, symbolStrategyService.displayName(holding.market, symbol), valuation.priceOf(symbol)),
                "unrealizedText" to signedMoneyAndPercent(amount, percent, KRW),
                "unrealizedCss" to pnlCss(amount),
                "totalValueText" to valuation.totalValueOf(heldKr.values).money(KRW),
            ),
        )
    }

    /** 국내 화면이므로 원화 요약만 보낸다. 평가손익은 실시간 현재가로 다시 계산한다. */
    private fun summaryView(snapshot: PortfolioSnapshot): PnlSummaryLiveView? {
        val summary = snapshot.summaryByCurrency[KRW] ?: return null
        val held = accountHoldingRepository.findAll().filter { it.market == Market.KR && it.quantity > 0 }
        val (amount, percent) = valuation.unrealizedOf(held)
        return PnlSummaryLiveView.of(summary, KRW, amount, percent, valuation.totalValueOf(held), cash.orderable(Market.KR))
    }

    private fun holdingViews(): List<HoldingLiveView> {
        val held = accountHoldingRepository.findAll().filter { it.quantity > 0 }.sortedBy { it.symbol }
        heldKr = held.filter { it.market == Market.KR }.associateBy { it.symbol }
        return held.map {
            HoldingLiveView.of(
                it,
                symbolStrategyService.displayName(it.market, it.symbol),
                if (it.market == Market.KR) valuation.priceOf(it.symbol) else null,
            )
        }
    }

    /**
     * 체결 내역 = 오늘(KST) 체결이 1주라도 있는 주문 전부(`order_fill`), 마지막 체결이 최근인 순. 하루치라 하루가 끝나면 비워진다.
     * 펼치면 그 주문의 건별 체결(원장 `kis_broker_fill` 줄)과 체결조회로 채운 몫이 보인다. 손익은 [PortfolioService] 가 확정한 값이다.
     */
    private fun todaysFills(snapshot: PortfolioSnapshot): List<OrderFillLiveView> {
        val today = OrderFills.today()
        val ledger = orderFills.projectedAll().filter { OrderFills.dayOf(it.filledAt) == today }.groupBy { it.orderNo }
        return fillStates.ofDay(today)
            .filter { it.filled.quantity > 0 }
            .map { row(it, ledger[it.orderNo].orEmpty(), snapshot) }
            .sortedByDescending { it.placedAtEpochMs }
    }

    private fun orderFillRow(
        orderNo: String,
        day: LocalDate,
        snapshot: PortfolioSnapshot = portfolioService.snapshot(),
    ): OrderFillLiveView? {
        val fill = fillStates.find(orderNo, day)?.takeIf { it.filled.quantity > 0 } ?: return null
        val ledger = orderFills.projected(orderNo, day)
        return row(fill, ledger, snapshot)
    }

    private fun row(
        fill: OrderFillState,
        ledger: List<com.quantlog.position.OrderFill>,
        snapshot: PortfolioSnapshot,
    ): OrderFillLiveView {
        val trade = tradeService.findByOrderNo(fill.market, fill.orderNo)?.takeIf { OrderFills.dayOf(it.executedAt) == fill.tradeDate }
        val pnl = trade?.id?.let { snapshot.realizedPnlByTradeId[it] }
        return OrderFillLiveView.of(fill, ledger, trade, pnl, symbolStrategyService.displayName(fill.market, fill.symbol))
    }

    private fun Trade.toView(pnl: RealizedPnl?) = OrderLiveView.of(this, symbolStrategyService.displayName(market, symbol), pnl)

    private fun broadcast(payload: Any) = sessions.broadcast(payload)

    private companion object {
        const val KRW = "KRW"
    }
}

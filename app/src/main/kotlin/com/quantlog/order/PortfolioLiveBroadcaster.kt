package com.quantlog.order

import com.fasterxml.jackson.databind.ObjectMapper
import com.quantlog.broker.Market
import com.quantlog.broker.PriceTick
import com.quantlog.broker.Side
import com.quantlog.position.AccountHolding
import com.quantlog.position.AccountHoldingRepository
import com.quantlog.position.FillAppliedEvent
import com.quantlog.position.HoldingsChangedEvent
import com.quantlog.position.OrderFills
import com.quantlog.position.OrderState
import com.quantlog.position.PortfolioService
import com.quantlog.position.PortfolioSnapshot
import com.quantlog.position.RealizedPnl
import com.quantlog.position.Trade
import com.quantlog.position.TradeChangedEvent
import com.quantlog.watchlist.SymbolStrategyService
import jakarta.annotation.PreDestroy
import mu.KotlinLogging
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry
import org.springframework.web.socket.handler.TextWebSocketHandler
import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

private val log = KotlinLogging.logger {}

/**
 * 주문이 접수·체결·취소되거나 보유 종목이 바뀔 때마다 열려 있는 모든 브라우저(/ws/portfolio)에 그 변화를 밀어준다.
 * 새로 연결되면 보유 종목·대기열·최근 체결 내역을 한 번에 보내(snapshot) 화면이 바로 채워지게 한다.
 */
@Component
class PortfolioLiveBroadcaster(
    private val portfolioService: PortfolioService,
    private val accountHoldingRepository: AccountHoldingRepository,
    private val orderFills: OrderFills,
    private val symbolStrategyService: SymbolStrategyService,
    private val objectMapper: ObjectMapper,
) {
    private val sessions = CopyOnWriteArraySet<WebSocketSession>()
    private val livePrices = ConcurrentHashMap<String, BigDecimal>()

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
        val filled = newestFirst.filter { it.state == OrderState.FILLED }.take(HISTORY_LIMIT)
        val waiting = newestFirst.filter { it.state != OrderState.FILLED && it.executedAt.isAfter(todayStart) }
        val orders = (waiting + filled).map { it.toView(snapshot.realizedPnlByTradeId[it.id]) }
        val snapshotMessage =
            mapOf(
                "type" to "snapshot",
                "orders" to orders,
                "fills" to recentFills(snapshot),
                "holdings" to holdingViews(),
                "summary" to summaryView(snapshot),
            )
        send(session, snapshotMessage)
    }

    fun disconnect(session: WebSocketSession) {
        sessions.remove(session)
    }

    @EventListener
    fun onTradeChanged(event: TradeChangedEvent) {
        if (sessions.isEmpty()) return
        val trade = event.trade
        // 실현손익은 체결된 매도에만 있고, 매매 기록 전체를 FIFO 로 다시 계산해야 해서 그때만 구한다.
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
    }

    /**
     * 체결통보 한 건이 반영되면 체결 내역에 한 줄을 바로 밀어 준다(주문 단위가 아니라 체결 단위). 매도면 오늘 손익 요약도 같이 보낸다.
     * 원장 저장이 커밋된 뒤에 불러야 요약에 그 체결이 들어간다.
     */
    @TransactionalEventListener(fallbackExecution = true)
    fun onFillApplied(event: FillAppliedEvent) {
        if (sessions.isEmpty()) return
        val fill = FillLiveView.of(event, symbolStrategyService.displayName(event.market, event.symbol))
        val message = mutableMapOf<String, Any?>("type" to "fill", "fill" to fill)
        if (event.side == Side.SELL) message["summary"] = summaryView(portfolioService.snapshot())
        broadcast(message)
    }

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
        livePrices[tick.symbol] = tick.price
        pushExecutor.execute { runCatching { pushPrice(tick.symbol) }.onFailure { log.warn(it) { "[주문 화면] 실시간 현재가 푸시 실패" } } }
    }

    @PreDestroy
    fun stopPricePush() {
        pushExecutor.shutdownNow()
    }

    private fun pushPrice(symbol: String) {
        val holding = heldKr[symbol] ?: return
        val (amount, percent) = unrealizedOf(heldKr.values)
        broadcast(
            mapOf(
                "type" to "price",
                "holding" to HoldingLiveView.of(holding, symbolStrategyService.displayName(holding.market, symbol), livePrices[symbol]),
                "unrealizedText" to signedMoneyAndPercent(amount, percent, KRW),
                "unrealizedCss" to pnlCss(amount),
                "totalValueText" to totalValueOf(heldKr.values).money(KRW),
            ),
        )
    }

    /** 보유 국내 종목 전체의 평가손익(금액, 퍼센트). 현재가는 실시간 틱이 있으면 그걸, 없으면 잔고 사본 값을 쓴다. */
    private fun unrealizedOf(held: Collection<AccountHolding>): Pair<BigDecimal, BigDecimal> {
        val cost = held.sumOf { it.avgCost.multiply(BigDecimal(it.quantity)) }
        val value = held.sumOf { (livePrices[it.symbol] ?: it.currentPrice).multiply(BigDecimal(it.quantity)) }
        val amount = value.subtract(cost)
        val percent = if (cost.signum() > 0) amount.multiply(BigDecimal(100)).divide(cost, MathContext.DECIMAL64) else BigDecimal.ZERO
        return amount to percent
    }

    /** 보유 종목 전체의 총 평가금액. 현재가는 [unrealizedOf] 와 같은 규칙. */
    private fun totalValueOf(held: Collection<AccountHolding>): BigDecimal =
        held.sumOf { (livePrices[it.symbol] ?: it.currentPrice).multiply(BigDecimal(it.quantity)) }

    /** 국내 화면이므로 원화 요약만 보낸다. 평가손익은 실시간 현재가로 다시 계산한다. */
    private fun summaryView(snapshot: PortfolioSnapshot): PnlSummaryLiveView? {
        val summary = snapshot.summaryByCurrency[KRW] ?: return null
        val held = accountHoldingRepository.findAll().filter { it.market == Market.KR && it.quantity > 0 }
        val (amount, percent) = unrealizedOf(held)
        return PnlSummaryLiveView.of(summary, KRW, amount, percent, totalValueOf(held))
    }

    private fun holdingViews(): List<HoldingLiveView> {
        val held = accountHoldingRepository.findAll().filter { it.quantity > 0 }.sortedBy { it.symbol }
        heldKr = held.filter { it.market == Market.KR }.associateBy { it.symbol }
        return held.map {
            HoldingLiveView.of(
                it,
                symbolStrategyService.displayName(it.market, it.symbol),
                if (it.market == Market.KR) livePrices[it.symbol] else null,
            )
        }
    }

    /**
     * 체결 내역 = 원장(`broker_fill`) 줄 + 원장에 없는 옛 체결 주문(주문 한 건을 한 줄로). 최근 순으로 [HISTORY_LIMIT] 건.
     * 원장 줄의 손익은 그 주문의 체결 직전 평단([com.quantlog.position.Trade.avgCostBefore]) 기준이다.
     */
    private fun recentFills(snapshot: PortfolioSnapshot): List<FillLiveView> {
        val ledger = orderFills.projectedAll()
        val ledgerOrders = ledger.map { it.market to it.orderNo }.toSet()
        val fromLedger =
            ledger.map {
                FillLiveView.of(
                    it,
                    snapshot.avgCostBeforeByOrder[it.market to it.orderNo],
                    symbolStrategyService.displayName(it.market, it.symbol),
                )
            }
        val legacy =
            snapshot.trades
                .filter { it.state == OrderState.FILLED && (it.market to it.orderNo) !in ledgerOrders }
                .map {
                    FillLiveView.ofLegacy(
                        it,
                        symbolStrategyService.displayName(it.market, it.symbol),
                        snapshot.realizedPnlByTradeId[it.id],
                    )
                }
        return (fromLedger + legacy).sortedByDescending { it.placedAtEpochMs }.take(HISTORY_LIMIT)
    }

    private fun Trade.toView(pnl: RealizedPnl?) = OrderLiveView.of(this, symbolStrategyService.displayName(market, symbol), pnl)

    private fun broadcast(payload: Any) = sessions.forEach { send(it, payload) }

    /** 체결통보 수신 스레드 등 여러 스레드가 부르므로 세션별로 직렬화한다(WebSocketSession 은 동시 전송이 안전하지 않다). */
    private fun send(
        session: WebSocketSession,
        payload: Any,
    ) {
        val text = TextMessage(objectMapper.writeValueAsString(payload))
        runCatching { synchronized(session) { if (session.isOpen) session.sendMessage(text) } }
            .onFailure {
                log.warn { "[주문 화면] 전송 실패, 세션을 뺀다: ${it.message}" }
                sessions.remove(session)
            }
    }

    private companion object {
        val KST: ZoneId = ZoneId.of("Asia/Seoul")
        const val HISTORY_LIMIT = 100
        const val KRW = "KRW"
    }
}

@Component
class PortfolioLiveWebSocketHandler(private val broadcaster: PortfolioLiveBroadcaster) : TextWebSocketHandler() {
    override fun afterConnectionEstablished(session: WebSocketSession) = broadcaster.connect(session)

    override fun afterConnectionClosed(
        session: WebSocketSession,
        status: CloseStatus,
    ) = broadcaster.disconnect(session)
}

@Configuration
@EnableWebSocket
class PortfolioLiveWebSocketConfig(private val handler: PortfolioLiveWebSocketHandler) : WebSocketConfigurer {
    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        registry.addHandler(handler, "/ws/portfolio").setAllowedOrigins("*")
    }
}

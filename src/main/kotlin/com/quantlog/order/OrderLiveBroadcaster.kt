package com.quantlog.order

import com.fasterxml.jackson.databind.ObjectMapper
import com.quantlog.broker.Market
import com.quantlog.broker.PriceTick
import com.quantlog.broker.Side
import com.quantlog.position.AccountHoldingRepository
import com.quantlog.position.HoldingsChangedEvent
import com.quantlog.position.OrderState
import com.quantlog.position.PortfolioService
import com.quantlog.position.PortfolioSnapshot
import com.quantlog.position.RealizedPnl
import com.quantlog.position.Trade
import com.quantlog.position.TradeChangedEvent
import com.quantlog.watchlist.SymbolStrategyService
import mu.KotlinLogging
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
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

private val log = KotlinLogging.logger {}

/**
 * 주문이 접수·체결·취소되거나 보유 종목이 바뀔 때마다 열려 있는 모든 브라우저(/ws/orders)에 그 변화를 밀어준다.
 * 새로 연결되면 보유 종목·대기열·최근 체결 내역을 한 번에 보내(snapshot) 화면이 바로 채워지게 한다.
 */
@Component
class OrderLiveBroadcaster(
    private val portfolioService: PortfolioService,
    private val accountHoldingRepository: AccountHoldingRepository,
    private val symbolStrategyService: SymbolStrategyService,
    private val objectMapper: ObjectMapper,
) {
    private val sessions = CopyOnWriteArraySet<WebSocketSession>()
    private val livePrices = ConcurrentHashMap<String, BigDecimal>()

    @Volatile
    private var priceDirty = false

    fun connect(session: WebSocketSession) {
        sessions.add(session)
        val snapshot = portfolioService.snapshot()
        val todayStart = LocalDate.now(KST).atStartOfDay(KST).toInstant()
        val newestFirst = snapshot.trades.sortedByDescending { it.executedAt }
        val filled = newestFirst.filter { it.state == OrderState.FILLED }.take(HISTORY_LIMIT)
        val waiting = newestFirst.filter { it.state != OrderState.FILLED && it.executedAt.isAfter(todayStart) }
        val orders = (waiting + filled).map { it.toView(snapshot.realizedPnlByTradeId[it.id]) }
        send(session, mapOf("type" to "snapshot", "orders" to orders, "holdings" to holdingViews(), "summary" to summaryView(snapshot)))
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

    /** 보유 종목이 바뀌면 전체 목록을 다시 보낸다(종목 수가 적다). 커밋된 뒤에 읽어야 해서 트랜잭션이 없으면 바로 실행한다. */
    @TransactionalEventListener(fallbackExecution = true)
    fun onHoldingsChanged(event: HoldingsChangedEvent) {
        if (sessions.isEmpty()) return
        broadcast(mapOf("type" to "holdings", "holdings" to holdingViews(), "summary" to summaryView(portfolioService.snapshot())))
    }

    /** 실시간 틱은 수신 스레드에서 오므로 가격만 적어두고, 화면 갱신은 [pushLivePrices] 가 1초에 한 번 모아서 보낸다. */
    @EventListener
    fun onPriceTick(tick: PriceTick) {
        if (sessions.isEmpty() || tick.market != Market.KR) return
        livePrices[tick.symbol] = tick.price
        priceDirty = true
    }

    @Scheduled(fixedDelay = PRICE_PUSH_MILLIS)
    fun pushLivePrices() {
        if (!priceDirty || sessions.isEmpty()) return
        priceDirty = false
        broadcast(mapOf("type" to "holdings", "holdings" to holdingViews(), "summary" to summaryView(portfolioService.snapshot())))
    }

    /** 국내 화면이므로 원화 요약만 보낸다. 평가손익은 실시간 현재가로 다시 계산한다. */
    private fun summaryView(snapshot: PortfolioSnapshot): PnlSummaryLiveView? {
        val summary = snapshot.summaryByCurrency[KRW] ?: return null
        val held = accountHoldingRepository.findAll().filter { it.market == Market.KR && it.quantity > 0 }
        val cost = held.sumOf { it.avgCost.multiply(BigDecimal(it.quantity)) }
        val value = held.sumOf { (livePrices[it.symbol] ?: it.currentPrice).multiply(BigDecimal(it.quantity)) }
        val amount = value.subtract(cost)
        val percent = if (cost.signum() > 0) amount.multiply(BigDecimal(100)).divide(cost, MathContext.DECIMAL64) else BigDecimal.ZERO
        return PnlSummaryLiveView.of(summary, KRW, amount, percent)
    }

    private fun holdingViews() =
        accountHoldingRepository.findAll()
            .filter { it.quantity > 0 }
            .sortedBy { it.symbol }
            .map {
                HoldingLiveView.of(
                    it,
                    symbolStrategyService.displayName(it.market, it.symbol),
                    if (it.market == Market.KR) livePrices[it.symbol] else null,
                )
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
        const val PRICE_PUSH_MILLIS = 1000L
    }
}

@Component
class OrderLiveWebSocketHandler(private val broadcaster: OrderLiveBroadcaster) : TextWebSocketHandler() {
    override fun afterConnectionEstablished(session: WebSocketSession) = broadcaster.connect(session)

    override fun afterConnectionClosed(
        session: WebSocketSession,
        status: CloseStatus,
    ) = broadcaster.disconnect(session)
}

@Configuration
@EnableWebSocket
class OrderLiveWebSocketConfig(private val handler: OrderLiveWebSocketHandler) : WebSocketConfigurer {
    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        registry.addHandler(handler, "/ws/orders").setAllowedOrigins("*")
    }
}

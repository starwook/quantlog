package com.quantlog.trading

import com.quantlog.broker.BrokerClient
import com.quantlog.broker.OrderRequest
import com.quantlog.broker.Quote
import com.quantlog.broker.Side
import com.quantlog.position.HoldingView
import com.quantlog.position.PortfolioService
import com.quantlog.position.TradeService
import com.quantlog.strategy.ExitSignal
import com.quantlog.strategy.FixedPercentExitRule
import com.quantlog.strategy.MartingaleCycle
import com.quantlog.strategy.MartingaleRule
import com.quantlog.watchlist.SymbolStrategyService
import mu.KotlinLogging
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap

private val log = KotlinLogging.logger {}

@ConfigurationProperties(prefix = "quantlog.exit")
data class ExitProperties(
    /** false 면 아무것도 하지 않는다. */
    val enabled: Boolean = false,
    /** 한 번 매도 주문을 낸 종목은 이 시간 동안 다시 팔지 않는다 (미체결 주문이 걸려 있을 수 있어서). */
    val cooldown: Duration = Duration.ofMinutes(10),
)

/**
 * 청산 스케줄러 (원칙: 청산은 기계적으로, AI 없이). 정규장 동안 주기적으로 보유 종목을 보고,
 * 평단 ±1%에 가장 가까운 호가(익절/손절 목표가)에 닿은 종목을 전량 매도한다.
 * 매도가는 현재가보다 한 호가 낮게 — 바로 체결되게 하면서 손해는 한 호가로 줄인다.
 * 보유 현황은 REST 잔고 조회가 아니라 PortfolioService(우리 매매 기록 DB)로 본다 — 이 계좌는 이 봇만
 * 쓰므로 우리 기록이 곧 정답이고, 잘못돼도 매도 주문 자체가 KIS 쪽 실제 잔고와 안 맞으면 거절된다
 * (2026-09-29: 이렇게 바꿔서 초당 요청 한도 걱정 없이 스케줄 주기를 1초로 줄임).
 * 현재가(quote)만 KIS 를 본다 — 실시간 WebSocket 이 켜져 있으면 그것도 캐시라 REST 호출은 없다.
 */
@Component
class ExitScheduler(
    private val broker: BrokerClient,
    private val riskGuard: RiskGuard,
    private val exitRule: FixedPercentExitRule,
    private val symbolStrategyService: SymbolStrategyService,
    private val tradeService: TradeService,
    private val portfolioService: PortfolioService,
    private val properties: ExitProperties,
) {
    private val lastSellAt = ConcurrentHashMap<String, Instant>()

    @Scheduled(
        fixedDelayString = "\${quantlog.exit.interval-millis:30000}",
        initialDelayString = "\${quantlog.exit.initial-delay-millis:15000}",
    )
    fun run() {
        if (properties.enabled) checkExits(ZonedDateTime.now())
    }

    fun checkExits(now: ZonedDateTime) {
        val snapshot =
            runCatching { portfolioService.snapshot() }
                .onFailure { log.warn(it) { "[청산 감시] 보유 현황 조회 실패" } }
                .getOrNull() ?: return

        snapshot.summaryByCurrency.values
            .flatMap { it.holdings }
            .filter { it.market.isTradable(now) }
            .forEach { holding ->
                runCatching { checkHolding(holding, now.toInstant()) }
                    .onFailure { log.warn(it) { "[청산 감시] 실패: ${holding.market} ${holding.symbol}" } }
            }
    }

    private fun checkHolding(
        holding: HoldingView,
        now: Instant,
    ) {
        if (holding.quantity <= 0) return
        val key = "${holding.market}:${holding.symbol}"
        val last = lastSellAt[key]
        if (last != null && Duration.between(last, now) < properties.cooldown) return

        val quote = broker.quote(holding.market, holding.symbol)
        // 익절·손절 %는 종목별 DB 설정(symbol_strategy). 설정 행이 없는 종목은 전역 설정(application.yml)을 쓴다.
        val config = symbolStrategyService.find(holding.market, holding.symbol)
        val rule = config?.let { FixedPercentExitRule(it.takeProfitPercent, it.stopLossPercent) } ?: exitRule
        var signal = rule.evaluate(holding.avgCost, quote)
        // 마틴게일 종목의 손절은 전역 손절(보류)이 아니라 최대 단계 매수 뒤에만 있는 별도 손절이다.
        if (signal == ExitSignal.HOLD && config?.martingale == true) {
            val cycle = MartingaleCycle.from(tradeService.trades(holding.market, holding.symbol))
            if (MartingaleRule(config.martingaleProperties()).shouldStopLoss(cycle, quote)) signal = ExitSignal.STOP_LOSS
        }
        if (signal == ExitSignal.HOLD) return

        sell(holding, quote, signal, rule)
        lastSellAt[key] = now
    }

    private fun sell(
        holding: HoldingView,
        quote: Quote,
        signal: ExitSignal,
        rule: FixedPercentExitRule,
    ) {
        val targets = rule.targets(holding.avgCost, quote)
        val request =
            OrderRequest(
                market = holding.market,
                symbol = holding.symbol,
                side = Side.SELL,
                quantity = holding.quantity,
                limitPrice = quote.price.subtract(quote.tickSize),
            )
        riskGuard.check(request)
        val receipt = broker.placeOrder(request)
        Thread.sleep(FILL_CHECK_WAIT_MILLIS)
        val filledPrice =
            runCatching { broker.filledPrice(request.market, receipt.orderNo) }
                .onFailure { log.warn(it) { "[체결가 조회 실패] ${request.market} ${receipt.orderNo} — 지정가로 표시됨" } }
                .getOrNull()
        val reason =
            "청산 스케줄러: $signal (평단 ${holding.avgCost} → 현재 ${quote.price}, " +
                "익절 ${targets.takeProfitPrice} / 손절 ${targets.stopLossPrice ?: "없음(마틴게일은 최대 단계 뒤에만)"})"
        tradeService.record(request, receipt, reason, filledPrice)
        log.info {
            "[청산] ${request.market} ${request.symbol} x${request.quantity} @ ${request.limitPrice} " +
                "주문번호=${receipt.orderNo} — $reason"
        }
    }

    private companion object {
        const val FILL_CHECK_WAIT_MILLIS = 2000L
    }
}

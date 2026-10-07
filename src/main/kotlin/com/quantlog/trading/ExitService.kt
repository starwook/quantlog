package com.quantlog.trading

import com.quantlog.broker.BrokerClient
import com.quantlog.broker.CallPriority
import com.quantlog.broker.Market
import com.quantlog.broker.OrderRequest
import com.quantlog.broker.Quote
import com.quantlog.broker.Side
import com.quantlog.broker.kis.KisApiException
import com.quantlog.position.AccountHoldingRepository
import com.quantlog.position.HoldingSyncService
import com.quantlog.position.TradeService
import com.quantlog.strategy.ExitSignal
import com.quantlog.strategy.FixedPercentExitRule
import com.quantlog.watchlist.SymbolStrategy
import com.quantlog.watchlist.SymbolStrategyService
import mu.KotlinLogging
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap

private val log = KotlinLogging.logger {}

@ConfigurationProperties(prefix = "quantlog.exit")
data class ExitProperties(
    /** false 면 아무것도 하지 않는다. */
    val enabled: Boolean = false,
    /** 매도 주문이 이 시간 안에 체결 확인이 안 되면 취소하고 다시 판정한다([ExitService.expirePending]). */
    val fillTimeout: Duration = Duration.ofSeconds(10),
)

/** 보유 종목 하나에서 청산 판단에 필요한 값. */
data class ExitPosition(val market: Market, val symbol: String, val quantity: Int, val avgCost: BigDecimal)

/**
 * 청산 판단·매도 (원칙: 청산은 기계적으로, AI 없이). "언제 부르느냐"는 모른다 — 폴링([ExitScheduler])이든
 * 실시간 틱([ExitTickListener])이든 [checkAll]/[checkSymbol] 만 부르면 된다.
 * 평단 ±1%에 가장 가까운 호가(익절/손절 목표가)에 닿은 종목을 전량 매도한다.
 * 매도가는 현재가보다 한 호가 낮게 — 바로 체결되게 하면서 손해는 한 호가로 줄인다.
 * 보유 현황은 REST 잔고 조회가 아니라 잔고 사본 테이블(AccountHolding)로 본다 — 매매 기록 전체를 다시 계산하는
 * PortfolioService 와 달리 한 행 조회라 틱마다 불러도 가볍다. 사본이 낡았어도 매도 주문 자체가 KIS 쪽 실제 잔고와
 * 안 맞으면 거절된다. 현재가(quote)만 KIS 를 본다 — 실시간 WebSocket 이 켜져 있으면 그것도 캐시라 REST 호출은 없다.
 * 같은 종목을 동시에 두 스레드가 판정하지 않도록 종목별 잠금을 둔다(틱이 연달아 와도 주문이 중복되지 않게).
 * 매도 주문을 낸 종목은 **그 체결이 확인될 때까지** 판정을 건너뛴다(시간 쿨다운 없음 — 마틴게일과 같은 방식). 주문 후
 * [ExitProperties.fillTimeout] 안에 체결 확인이 안 되면 주문을 취소하고, 다음 틱부터 바로 다시 판정한다.
 */
@Component
class ExitService(
    private val broker: BrokerClient,
    private val riskGuard: RiskGuard,
    private val exitRule: FixedPercentExitRule,
    private val symbolStrategyService: SymbolStrategyService,
    private val tradeService: TradeService,
    private val accountHoldingRepository: AccountHoldingRepository,
    private val holdingSync: HoldingSyncService,
    private val properties: ExitProperties,
) {
    /** 매도 주문은 냈는데 체결 확인이 아직 안 된 종목. */
    private val pending = PendingOrders("청산", broker, tradeService, holdingSync, properties.fillTimeout)
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    /** 증권사가 "장 시작 전"으로 거부한 종목이 이 시각까지 판정을 쉰다. */
    private val marketClosedUntil = ConcurrentHashMap<String, Instant>()

    /** 보유 종목 전체를 판정한다. [exclude] 가 true 인 종목은 다른 경로(실시간)가 맡고 있으니 건너뛴다. */
    fun checkAll(
        now: ZonedDateTime,
        exclude: (Market, String) -> Boolean = { _, _ -> false },
    ) {
        val positions =
            runCatching { accountHoldingRepository.findAll().map { ExitPosition(it.market, it.symbol, it.quantity, it.avgCost) } }
                .onFailure { log.warn(it) { "[청산 감시] 보유 현황 조회 실패" } }
                .getOrNull() ?: return

        positions
            .filterNot { exclude(it.market, it.symbol) }
            .forEach { check(it, now) }
    }

    /** 이 종목 하나만 판정한다. 보유 중이 아니면 아무것도 안 한다. */
    fun checkSymbol(
        market: Market,
        symbol: String,
        now: ZonedDateTime,
    ) {
        val holding = accountHoldingRepository.findByMarketAndSymbol(market, symbol) ?: return
        check(ExitPosition(market, symbol, holding.quantity, holding.avgCost), now)
    }

    private fun check(
        position: ExitPosition,
        now: ZonedDateTime,
    ) {
        if (position.quantity <= 0 || !position.market.isTradable(now)) return
        val key = "${position.market}:${position.symbol}"
        if (marketClosedUntil[key]?.isAfter(now.toInstant()) == true) return
        if (!inFlight.add(key)) return
        try {
            checkHolding(position, key)
        } catch (e: KisApiException) {
            if (e.isMarketNotOpen) {
                // 모의투자는 프리마켓 시간에도 해외 주문을 거부한다. 3초마다 다시 내 봐야 같은 거부만 쌓이므로 잠시 쉰다.
                marketClosedUntil[key] = now.toInstant().plus(MARKET_CLOSED_BACKOFF)
                log.info { "[청산 감시] ${position.market} ${position.symbol} 장 시작 전 거부 — ${MARKET_CLOSED_BACKOFF.toMinutes()}분 쉼" }
            } else {
                log.warn(e) { "[청산 감시] 실패: ${position.market} ${position.symbol}" }
            }
        } catch (e: Exception) {
            log.warn(e) { "[청산 감시] 실패: ${position.market} ${position.symbol}" }
        } finally {
            inFlight.remove(key)
        }
    }

    private fun checkHolding(
        holding: ExitPosition,
        key: String,
    ) {
        if (pending.isWaiting(key)) return

        val quote = broker.quote(holding.market, holding.symbol)
        // 익절·손절 %는 종목별 DB 설정(symbol_strategy). 설정 행이 아예 없는 종목만 application.yml 의 기본값을 대비책으로 쓴다.
        val config = symbolStrategyService.find(holding.market, holding.symbol)
        val rule = config?.let { FixedPercentExitRule(it.takeProfitPercent, it.stopLossPercent) } ?: exitRule
        val signal = rule.evaluate(holding.avgCost, quote)
        val percentRule = percentRuleText(signal, config)
        if (signal == ExitSignal.HOLD) return

        sell(holding, quote, signal, rule, percentRule, key, signaledAt = System.currentTimeMillis())
    }

    private fun sell(
        holding: ExitPosition,
        quote: Quote,
        signal: ExitSignal,
        rule: FixedPercentExitRule,
        percentRule: String,
        key: String,
        signaledAt: Long,
    ) {
        // 청산 주문 흐름은 스케줄러의 일반 호출보다 먼저 나간다(broker/CallPriority.kt).
        CallPriority.urgent {
            val targets = rule.targets(holding.avgCost, quote)
            val request =
                OrderRequest(
                    market = holding.market,
                    symbol = holding.symbol,
                    side = Side.SELL,
                    quantity = holding.quantity,
                    limitPrice = quote.oneTickBelow(),
                )
            riskGuard.check(request)
            val receipt = broker.placeOrder(request)
            pending.track(key, request, receipt, Instant.now())
            log.info { "[청산 지연] ${request.market} ${request.symbol} 신호→주문 접수 ${System.currentTimeMillis() - signaledAt}ms" }
            val reason =
                "청산 스케줄러: $percentRule — $signal (평단 ${holding.avgCost} → 현재 ${quote.price}, " +
                    "익절 ${targets.takeProfitPrice} / 손절 ${targets.stopLossPrice ?: "없음"})"
            // 체결가 조회를 기다리지 않는다 — 체결통보가 이미 왔으면 TradeService 가 보관한 체결가를 채우고, 아니면 통보가 올 때 채운다.
            tradeService.record(request, receipt, reason)
            log.info {
                "[청산] ${request.market} ${request.symbol} x${request.quantity} @ ${request.limitPrice} " +
                    "주문번호=${receipt.orderNo} — $reason"
            }
        }
    }

    /** 매도 사유에 남길 "몇 % 법칙으로 팔았는지". 종목 설정 행이 없으면 기본값을 써서 % 를 알 수 없다. */
    private fun percentRuleText(
        signal: ExitSignal,
        config: SymbolStrategy?,
    ): String =
        when (signal) {
            ExitSignal.TAKE_PROFIT -> "익절 +${config?.takeProfitPercent?.stripTrailingZeros()?.toPlainString() ?: "전역 설정 "}% 법칙"
            ExitSignal.STOP_LOSS -> "손절 -${config?.stopLossPercent?.stripTrailingZeros()?.toPlainString() ?: "전역 설정 "}% 법칙"
            else -> ""
        }

    /** 주문한 지 [ExitProperties.fillTimeout] 이 지났는데도 체결이 확인되지 않은 매도 주문을 취소한다(주기 호출). */
    fun expirePending(now: ZonedDateTime) {
        pending.expire(now) { key, action ->
            if (inFlight.add(key)) {
                try {
                    action()
                } finally {
                    inFlight.remove(key)
                }
            }
        }
    }

    private companion object {
        val MARKET_CLOSED_BACKOFF: Duration = Duration.ofMinutes(5)
    }
}

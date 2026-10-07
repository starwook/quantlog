package com.quantlog.trading

import com.quantlog.broker.BrokerClient
import com.quantlog.broker.CallPriority
import com.quantlog.broker.Market
import com.quantlog.broker.OrderRequest
import com.quantlog.broker.Quote
import com.quantlog.broker.Side
import com.quantlog.position.AccountHoldingRepository
import com.quantlog.position.HoldingSyncService
import com.quantlog.position.TradeService
import com.quantlog.strategy.MartingaleCycle
import com.quantlog.strategy.MartingaleRule
import com.quantlog.watchlist.SymbolStrategyService
import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap

private val log = KotlinLogging.logger {}

/**
 * 마틴게일 추가 매수 판정·주문 (국내 실시간 틱 경로). 틱이 오면 그 종목만, 평단 -n% 에 닿았는지 DB 평단과 틱 가격만 비교해서 본다.
 * 호출 없이 끝나는 비교라 틱마다 불러도 가볍고, 조건이 맞을 때만 시세·규칙 판정과 주문으로 들어간다.
 *
 * 추가 매수 수량·가격은 DB 의 보유 수량·평단으로 계산하므로, 주문을 낸 종목은 **그 체결이 DB 에 반영될 때까지** 판정을 건너뛴다
 * ("단계 진행 중"). 반영은 체결통보([HoldingSyncService.applyFill])가 1~2초 안에 한다. 주문 후 [EntryProperties.fillTimeout] 안에
 * 체결 확인이 안 되면 주문을 취소하고, 다음 틱부터 다시 판정한다(2026-10-07 사용자 결정: 취소 뒤 쿨다운 없이 바로 재판정).
 * 실시간이 커버하지 않는 종목(해외, 연결 끊김)은 [EntryScheduler] 의 폴링이 맡는다.
 */
@Component
class MartingaleService(
    private val broker: BrokerClient,
    private val riskGuard: RiskGuard,
    private val symbolStrategyService: SymbolStrategyService,
    private val tradeService: TradeService,
    private val accountHoldingRepository: AccountHoldingRepository,
    private val holdingSync: HoldingSyncService,
    private val properties: EntryProperties,
) {
    /** 주문은 냈는데 체결이 아직 DB 에 반영 안 된 종목. */
    private val pending = PendingOrders("마틴게일", broker, tradeService, holdingSync, properties.fillTimeout)
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val lastFailureAt = ConcurrentHashMap<String, Instant>()

    /** [tickPrice] 는 방금 온 체결가. 보유 중이 아니거나 마틴게일이 꺼진 종목, 아직 트리거까지 안 내려온 경우엔 아무것도 하지 않는다. */
    fun checkSymbol(
        market: Market,
        symbol: String,
        tickPrice: BigDecimal,
        now: ZonedDateTime,
    ) {
        if (!market.isTradable(now)) return
        val key = "$market:$symbol"
        if (!inFlight.add(key)) return
        try {
            check(market, symbol, key, tickPrice, now)
        } finally {
            inFlight.remove(key)
        }
    }

    private fun check(
        market: Market,
        symbol: String,
        key: String,
        tickPrice: BigDecimal,
        now: ZonedDateTime,
    ) {
        if (pending.isWaiting(key)) return
        val failedAt = lastFailureAt[key]
        if (failedAt != null && Duration.between(failedAt, now.toInstant()) < properties.cooldown) return

        val config = symbolStrategyService.find(market, symbol)?.takeIf { it.martingale } ?: return
        val holding = accountHoldingRepository.findByMarketAndSymbol(market, symbol)?.takeIf { it.quantity > 0 } ?: return

        // 틱마다 하는 값싼 사전 비교. 호가 단위 반올림 때문에 규칙의 트리거가 평단 × (1 - n%) 보다 한두 호가 높을 수 있어 여유를 둔다.
        val dropRatio = BigDecimal.ONE.subtract(config.martingaleDropPercent.movePointLeft(2))
        if (tickPrice > holding.avgCost.multiply(dropRatio.add(PRECHECK_MARGIN))) return

        val rule = MartingaleRule(config.martingaleProperties())
        val cycle = MartingaleCycle.from(tradeService.trades(market, symbol)).withAccount(holding.quantity, holding.avgCost)
        val quote = broker.quote(market, symbol)
        val quantity = rule.nextQuantity(cycle, quote) ?: return
        val average = cycle.averagePrice!!
        val reason =
            "마틴게일 틱 판정: -${config.martingaleDropPercent.stripTrailingZeros().toPlainString()}% 법칙 " +
                "${cycle.stage + 1}단계 — 평단 $average 대비 ${rule.addOnTriggerPrice(average, quote)} 이하로 하락 " +
                "(틱 $tickPrice) → 보유 ${cycle.quantity}주 기준 ${quantity}주 추가 매수 (MartingaleRule)"
        runCatching { buy(market, symbol, quantity, quote, reason, key, now.toInstant()) }
            .onFailure {
                lastFailureAt[key] = now.toInstant()
                throw it
            }
    }

    private fun buy(
        market: Market,
        symbol: String,
        quantity: Int,
        quote: Quote,
        reason: String,
        key: String,
        now: Instant,
    ) {
        // 주문 흐름은 스케줄러의 일반 호출보다 먼저 나간다(broker/CallPriority.kt).
        CallPriority.urgent {
            val limitPrice = quote.roundToTick(quote.price.multiply(BigDecimal.ONE.add(EntryScheduler.BUY_OFFSET)), RoundingMode.CEILING)
            val request = OrderRequest(market, symbol, Side.BUY, quantity, limitPrice)
            riskGuard.checkBuy(request)
            val receipt = broker.placeOrder(request)
            pending.track(key, request, receipt, now)
            // 체결가 조회를 기다리지 않는다 — 체결통보가 이미 왔으면 TradeService 가 보관한 체결가를 채우고, 아니면 통보가 올 때 채운다.
            tradeService.record(request, receipt, reason)
            log.info { "[마틴게일 주문] $market $symbol x$quantity @ $limitPrice 주문번호=${receipt.orderNo} — $reason" }
        }
    }

    /** 주문한 지 [EntryProperties.fillTimeout] 이 지났는데도 체결이 반영되지 않은 주문을 취소한다(주기 호출). */
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
        /** 호가 단위 반올림 오차(가장 큰 호가는 가격의 약 0.2%)를 덮는 여유 0.3%. */
        val PRECHECK_MARGIN: BigDecimal = BigDecimal("0.003")
    }
}

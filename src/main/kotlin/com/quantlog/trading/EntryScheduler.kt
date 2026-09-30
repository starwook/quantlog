package com.quantlog.trading

import com.quantlog.broker.BrokerClient
import com.quantlog.broker.OrderRequest
import com.quantlog.broker.Side
import com.quantlog.marketdata.MarketDataService
import com.quantlog.position.PortfolioService
import com.quantlog.position.TradeService
import com.quantlog.strategy.EntrySignal
import com.quantlog.strategy.MartingaleEntryRule
import com.quantlog.strategy.SupportBounceEntryRule
import com.quantlog.watchlist.WatchedSymbol
import mu.KotlinLogging
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap

private val log = KotlinLogging.logger {}
private val KST: ZoneId = ZoneId.of("Asia/Seoul")
private val NEW_YORK: ZoneId = ZoneId.of("America/New_York")

@ConfigurationProperties(prefix = "quantlog.entry")
data class EntryProperties(
    /** false 면 아무것도 하지 않는다. */
    val enabled: Boolean = false,
    /** 한 번 산 종목은 이 시간 동안 다시 신호를 봐도 사지 않는다. */
    val cooldown: Duration = Duration.ofMinutes(10),
    /** 주문 1건 수량. 검증 전 규칙이라 작게 시작한다. */
    val quantityPerOrder: Int = 1,
)

/**
 * 진입 스케줄러 (분봉 저점 근접+반등 신호, AI 없이 순수 규칙 — playbook/principles.md "아직 정하는 중").
 * 정규장(국내) 또는 프리마켓~애프터마켓(미국, Market.isTradable) 동안 [WatchedSymbol] 전체의 분봉을 받아
 * DB에 쌓는다 — 차트·백테스트가 쓸 데이터라 매매 대상 여부와 무관하게 항상 수집한다
 * (2026-09-29: "SK하이닉스는 화면엔 있는데 분봉이 안 쌓인다"는 지적으로, 수집 대상과 매매 대상을 분리함).
 * 매매는 그중 [WatchedSymbol.autoTradeEnabled] 인 것만 — SupportBounceEntryRule 이 BUY 를 내면 소량
 * 매수 1건을 건다. 이미 보유 중인 종목은 다시 사지 않는다 — 단 [WatchedSymbol.martingale] 종목(삼성전자)은
 * 예외로, 직전 매수가 대비 1% 떨어질 때마다 직전 수량의 2배를 추가 매수한다(2026-09-30, 물타기 금지 원칙 폐기). 주문은
 * SmokeTestRunner/ExitScheduler 와 같은 RiskGuard.checkBuy(자본 배분·하루 손실 킬스위치 포함) →
 * 주문 → 매매 기록 순서를 거친다. "이미 보유 중인지"는 REST 잔고 조회 대신 PortfolioService(우리
 * 매매 기록 DB)로 본다 — ExitScheduler 와 같은 이유(초당 요청 한도 없이 스케줄 주기를 1초로 줄이기 위함).
 */
@Component
class EntryScheduler(
    private val broker: BrokerClient,
    private val riskGuard: RiskGuard,
    private val entryRule: SupportBounceEntryRule,
    private val martingaleRule: MartingaleEntryRule,
    private val marketDataService: MarketDataService,
    private val tradeService: TradeService,
    private val portfolioService: PortfolioService,
    private val watchedSymbols: List<WatchedSymbol>,
    private val properties: EntryProperties,
) {
    private val lastSignalAt = ConcurrentHashMap<String, Instant>()
    private val lastAddOnFailureAt = ConcurrentHashMap<String, Instant>()

    @Scheduled(
        fixedDelayString = "\${quantlog.entry.interval-millis:30000}",
        initialDelayString = "\${quantlog.entry.initial-delay-millis:20000}",
    )
    fun run() {
        if (properties.enabled) checkEntries(ZonedDateTime.now(KST))
    }

    fun checkEntries(now: ZonedDateTime) {
        val snapshot =
            runCatching { portfolioService.snapshot() }
                .onFailure { log.warn(it) { "[진입 감시] 보유 현황 조회 실패" } }
                .getOrNull() ?: return

        watchedSymbols.forEach { watched ->
            if (!watched.market.isTradable(now)) return@forEach

            // 분봉 수집은 매매 대상 여부와 무관하게 항상 한다.
            runCatching { marketDataService.fetchAndStoreRecentMinutes(watched.market, watched.symbol, LocalTime.now(KST)) }
                .onFailure { log.warn(it) { "[분봉 수집] 실패: ${watched.market} ${watched.symbol}" } }

            if (!watched.autoTradeEnabled) return@forEach

            // KRW 보유가 하나도 없으면(또는 USD) 요약 맵에 해당 통화 키 자체가 없다 — 조회 실패와 구분해야 한다.
            val holdingSymbols = snapshot.summaryByCurrency[watched.market.currency]?.holdings?.map { it.symbol }?.toSet() ?: emptySet()
            val holding = watched.symbol in holdingSymbols
            if (holding && !watched.martingale) return@forEach

            runCatching { if (holding) checkAddOn(watched, now.toInstant()) else checkTarget(watched, now.toInstant()) }
                .onFailure { log.warn(it) { "[진입 감시] 실패: ${watched.market} ${watched.symbol}" } }
        }
    }

    /**
     * 보유 중인 martingale 종목: 직전 매수가 대비 1% 이상 떨어졌으면 직전 매수 수량의 2배를 산다.
     * 신호가 아니라 가격 조건이라 성공한 매수 뒤엔 쿨다운을 걸지 않는다(다음 트리거는 또 -1%가 필요해서 자연히 걸러짐).
     * 실패(리스크 가드 거부 등)했을 땐 매초 재시도·로그 스팸을 막으려고 쿨다운을 건다.
     * 직전 매수가는 체결가가 아직 안 채워졌으면 지정가(현재가+0.5%)라 최대 0.5% 어긋날 수 있다.
     */
    private fun checkAddOn(
        watched: WatchedSymbol,
        now: Instant,
    ) {
        val key = "${watched.market}:${watched.symbol}"
        val lastFailure = lastAddOnFailureAt[key]
        if (lastFailure != null && Duration.between(lastFailure, now) < properties.cooldown) return

        val lastBuy = tradeService.lastBuy(watched.market, watched.symbol) ?: return
        val quote = broker.quote(watched.market, watched.symbol)
        val lastBuyPrice = lastBuy.filledPrice ?: lastBuy.orderPrice
        val quantity = martingaleRule.nextQuantity(lastBuyPrice, lastBuy.quantity, quote) ?: return

        val reason =
            "진입 스케줄러: 직전 매수가 $lastBuyPrice 대비 ${martingaleRule.triggerPrice(lastBuyPrice, quote)} 이하로 하락 → " +
                "직전 수량 ${lastBuy.quantity}주의 배수 ${quantity}주 추가 매수 (MartingaleEntryRule)"
        runCatching { buy(watched, quantity, reason) }
            .onFailure {
                lastAddOnFailureAt[key] = now
                throw it
            }
    }

    private fun checkTarget(
        watched: WatchedSymbol,
        now: Instant,
    ) {
        val key = "${watched.market}:${watched.symbol}"
        val last = lastSignalAt[key]
        if (last != null && Duration.between(last, now) < properties.cooldown) return

        val localDate = LocalDate.now(if (watched.market.isOverseas) NEW_YORK else KST)
        val candles = marketDataService.recentCandles(watched.market, watched.symbol, localDate)
        if (entryRule.evaluate(candles) != EntrySignal.BUY) return

        // 실패해도(리스크 가드 등) 다음 사이클마다 재시도해 로그를 스팸하지 않도록, 신호 시점에 바로 쿨다운을 건다.
        lastSignalAt[key] = now
        buy(watched, properties.quantityPerOrder, "진입 스케줄러: 당일 저점 근접+반등 신호 (SupportBounceEntryRule, 검증 전)")
    }

    private fun buy(
        watched: WatchedSymbol,
        quantity: Int,
        reason: String,
    ) {
        val quote = broker.quote(watched.market, watched.symbol)
        val limitPrice = quote.roundToTick(quote.price.multiply(BigDecimal.ONE.add(BUY_OFFSET)), RoundingMode.CEILING)
        val request = OrderRequest(watched.market, watched.symbol, Side.BUY, quantity, limitPrice)
        riskGuard.checkBuy(request)
        val receipt = broker.placeOrder(request)
        Thread.sleep(FILL_CHECK_WAIT_MILLIS)
        val filledPrice =
            runCatching { broker.filledPrice(request.market, receipt.orderNo) }
                .onFailure { log.warn(it) { "[체결가 조회 실패] ${request.market} ${receipt.orderNo} — 지정가로 표시됨" } }
                .getOrNull()
        tradeService.record(request, receipt, reason, filledPrice)
        log.info {
            "[진입] ${request.market} ${request.symbol} x${request.quantity} @ ${request.limitPrice} " +
                "주문번호=${receipt.orderNo} — $reason"
        }
    }

    private companion object {
        const val FILL_CHECK_WAIT_MILLIS = 2000L
        val BUY_OFFSET: BigDecimal = BigDecimal("0.005")
    }
}

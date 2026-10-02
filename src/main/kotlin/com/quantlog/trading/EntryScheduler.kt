package com.quantlog.trading

import com.quantlog.broker.BrokerClient
import com.quantlog.broker.CallPriority
import com.quantlog.broker.OrderRequest
import com.quantlog.broker.Side
import com.quantlog.marketdata.MarketDataService
import com.quantlog.position.HoldingSyncService
import com.quantlog.position.PortfolioService
import com.quantlog.position.PortfolioSnapshot
import com.quantlog.position.TradeService
import com.quantlog.strategy.EntrySignal
import com.quantlog.strategy.MartingaleCycle
import com.quantlog.strategy.MartingaleRule
import com.quantlog.strategy.SupportBounceEntryRule
import com.quantlog.watchlist.SymbolStrategy
import com.quantlog.watchlist.SymbolStrategyService
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
)

/**
 * 진입 스케줄러 (마틴게일 / 저점 판단 진입(분봉 저점 근접+반등 신호) / 주기(n분) 재매수 — 종목별 완전 별개 옵션, AI 없이 순수 규칙 — playbook/principles.md "아직 정하는 중").
 * 정규장(국내) 또는 프리마켓~애프터마켓(미국, Market.isTradable) 동안 DB 감시 종목(symbol_strategy) 전체의 분봉을 받아
 * DB에 쌓는다 — 차트·백테스트가 쓸 데이터라 매매 대상 여부와 무관하게 항상 수집한다
 * (2026-09-29: "SK하이닉스는 화면엔 있는데 분봉이 안 쌓인다"는 지적으로, 수집 대상과 매매 대상을 분리함).
 * 매매는 그중 DB 설정의 매수 옵션이 켜진 종목만 마틴게일(보유 중 추가 매수) / 주기 재매수(보유 0주일 때 종목별 n분마다 설정 수량) /
 * 저점 판단 진입(보유 수량 무관, 신호 시 1주)을 각자 켜진 대로 독립 실행한다([checkMartingale]·[checkPeriodicRebuy]·[checkTarget]).
 * 주문은
 * SmokeTestRunner/ExitScheduler 와 같은 RiskGuard.checkBuy(자본 배분·하루 손실 킬스위치 포함) →
 * 주문 → 매매 기록 순서를 거친다. "이미 보유 중인지"는 REST 잔고 조회 대신 PortfolioService(우리
 * 매매 기록 DB)로 본다 — ExitScheduler 와 같은 이유(초당 요청 한도 없이 스케줄 주기를 1초로 줄이기 위함).
 */
@Component
class EntryScheduler(
    private val broker: BrokerClient,
    private val riskGuard: RiskGuard,
    private val entryRule: SupportBounceEntryRule,
    private val symbolStrategyService: SymbolStrategyService,
    private val marketDataService: MarketDataService,
    private val tradeService: TradeService,
    private val portfolioService: PortfolioService,
    private val holdingSync: HoldingSyncService,
    private val properties: EntryProperties,
) {
    private val lastSignalAt = ConcurrentHashMap<String, Instant>()
    private val lastMartingaleFailureAt = ConcurrentHashMap<String, Instant>()
    private val lastRebuyCheckAt = ConcurrentHashMap<String, Instant>()

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

        // 감시 종목 목록도 DB 가 정본이다 — 매 주기 새로 읽어서 종목을 추가·삭제하면 바로 반영된다.
        val watchlist =
            runCatching { symbolStrategyService.all() }
                .onFailure { log.warn(it) { "[진입 감시] 감시 종목 조회 실패" } }
                .getOrNull() ?: return

        watchlist.forEach { watched ->
            if (!watched.market.isTradable(now)) return@forEach

            // 분봉 수집은 매매 대상 여부와 무관하게 항상 한다.
            runCatching { marketDataService.fetchAndStoreRecentMinutes(watched.market, watched.symbol, LocalTime.now(KST)) }
                .onFailure { log.warn(it) { "[분봉 수집] 실패: ${watched.market} ${watched.symbol}" } }

            // 무엇을 살지는 같은 행(symbol_strategy)의 매수 옵션이 정한다 — 하나도 안 켜져 있으면 아래 세 분기가 모두 건너뛰어진다.
            // 이 종목에 잔고 동기화보다 늦은 주문이 있으면 보유 현황이 낡았다 — 중복 매수를 막으려고 다음 동기화까지 미룬다.
            if (holdingSync.hasUnsyncedTrade(watched.market, watched.symbol)) return@forEach

            // 세 진입 옵션은 완전히 별개다 — 서로의 조건·결과를 보지 않고 각자 판단한다(같은 주기에 둘 이상 주문이 나갈 수도 있다).
            val held =
                snapshot.summaryByCurrency[watched.market.currency]?.holdings?.any {
                    it.market == watched.market && it.symbol == watched.symbol
                } == true
            if (watched.martingale && held) {
                runCatching { checkMartingale(watched, snapshot, now.toInstant()) }
                    .onFailure { log.warn(it) { "[마틴게일] 실패: ${watched.market} ${watched.symbol}" } }
            }
            if (watched.periodicRebuy) {
                runCatching { checkPeriodicRebuy(watched, held, now.toInstant()) }
                    .onFailure { log.warn(it) { "[주기 재매수] 실패: ${watched.market} ${watched.symbol}" } }
            }
            if (watched.supportBounceEntry) {
                runCatching { checkTarget(watched, now.toInstant()) }
                    .onFailure { log.warn(it) { "[저점 판단 진입] 실패: ${watched.market} ${watched.symbol}" } }
            }
        }
    }

    /**
     * 마틴게일: 보유 중일 때만, 평단 -0.5% 에 닿으면 보유 수량이 2배가 되게 추가 매수하고 최대 단계면 더 사지 않는다(손절은 ExitScheduler).
     * 단계는 매매 기록에서, 보유 수량·평단은 KIS 잔고 테이블에서 온다([MartingaleCycle]). 첫 진입·매도 뒤 재진입은 하지 않는다(2026-10-02 폐기).
     * 가격 조건이라 성공한 매수 뒤엔 쿨다운이 필요 없다(다음 트리거는 또 -0.5%가 필요해서 자연히 걸러짐).
     * 실패(리스크 가드 거부 등)했을 땐 매초 재시도·로그 스팸을 막으려고 쿨다운을 건다.
     * 평단은 체결가가 아직 안 채워졌으면 지정가(현재가+0.5%)라 최대 0.5% 어긋날 수 있다.
     */
    private fun checkMartingale(
        watched: SymbolStrategy,
        snapshot: PortfolioSnapshot,
        now: Instant,
    ) {
        val martingaleRule = MartingaleRule(watched.martingaleProperties())
        val key = "${watched.market}:${watched.symbol}"
        val lastFailure = lastMartingaleFailureAt[key]
        if (lastFailure != null && Duration.between(lastFailure, now) < properties.cooldown) return

        val held =
            snapshot.summaryByCurrency[watched.market.currency]?.holdings
                ?.firstOrNull { it.market == watched.market && it.symbol == watched.symbol }
        val cycle = MartingaleCycle.from(tradeService.trades(watched.market, watched.symbol)).withAccount(held?.quantity, held?.avgCost)
        val quote = broker.quote(watched.market, watched.symbol)
        val quantity = martingaleRule.nextQuantity(cycle, quote) ?: return
        val average = cycle.averagePrice!!
        val reason =
            "진입 스케줄러: 마틴게일 -${watched.martingaleDropPercent.stripTrailingZeros().toPlainString()}% 법칙 " +
                "${cycle.stage + 1}단계 — 평단 $average 대비 " +
                "${martingaleRule.addOnTriggerPrice(average, quote)} 이하로 하락 → 보유 ${cycle.quantity}주 기준 ${quantity}주 " +
                "추가 매수 (MartingaleRule)"
        runCatching { buy(watched, quantity, reason) }
            .onFailure {
                lastMartingaleFailureAt[key] = now
                throw it
            }
    }

    /**
     * 주기 재매수: 주문 기록과 무관하게 종목 설정의 간격([SymbolStrategy.periodicRebuyIntervalMinutes], 분)마다 계속 돌면서, 그 순간 보유가 0주이면 종목 설정의 수량만큼 산다.
     * 보유 중이어도 주기 시각은 흘러간다(보유 중엔 건너뛰고 다음 주기에 다시 본다). 실패해도 다음 주기까지 쉰다.
     */
    private fun checkPeriodicRebuy(
        watched: SymbolStrategy,
        held: Boolean,
        now: Instant,
    ) {
        val key = "${watched.market}:${watched.symbol}"
        val last = lastRebuyCheckAt[key]
        val interval = Duration.ofMinutes(watched.periodicRebuyIntervalMinutes.toLong())
        if (last != null && Duration.between(last, now) < interval) return
        lastRebuyCheckAt[key] = now
        if (held) return
        buy(watched, watched.periodicRebuyQuantity, "진입 스케줄러: 보유 없음 — ${watched.periodicRebuyIntervalMinutes}분 재매수")
    }

    /** 저점 판단 진입: 보유 수량과 상관없이 신호가 뜨면 산다. 쿨다운은 [EntryProperties.cooldown]. */
    private fun checkTarget(
        watched: SymbolStrategy,
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
        buy(watched, watched.supportBounceQuantity, "진입 스케줄러: 당일 저점 근접+반등 신호 (SupportBounceEntryRule, 검증 전)")
    }

    private fun buy(
        watched: SymbolStrategy,
        quantity: Int,
        reason: String,
    ) {
        // 주문 흐름 전체(시세→주문→체결가 조회)는 스케줄러의 일반 호출보다 먼저 나간다(broker/CallPriority.kt).
        CallPriority.urgent {
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
    }

    private companion object {
        const val FILL_CHECK_WAIT_MILLIS = 2000L
        val BUY_OFFSET: BigDecimal = BigDecimal("0.005")
    }
}

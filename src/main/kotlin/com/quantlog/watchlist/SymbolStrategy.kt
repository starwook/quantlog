package com.quantlog.watchlist

import com.quantlog.broker.Market
import com.quantlog.strategy.MartingaleProperties
import com.quantlog.strategy.StrategyProperties
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import mu.KotlinLogging
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

private val log = KotlinLogging.logger {}

/**
 * 종목별 매매 설정. 오늘 살 종목, 익절·손절 %, 마틴게일 파라미터처럼 원칙이 바뀔 때마다 만지는 값을
 * 코드가 아니라 DB(`symbol_strategy` 테이블)에 둔다 — 값을 UPDATE 하면 재시작 없이 스케줄러의 다음 주기(1초)부터 반영된다.
 * 스케줄러는 매번 읽기 때문에 캐시가 없다. 종목 목록 자체(분봉 수집·차트 대상)는 [WatchedSymbol] enum 이 그대로 갖는다.
 *
 * 비율은 % 단위다(0.5 = 0.5%). 손절이 null 이면 전역 손절은 보류(안 나감)다.
 * 행이 없는 종목은 자동 매수하지 않는다. 아래 [SymbolStrategySeeder] 가 없는 행만 기본값으로 채운다(기존 값은 안 덮어씀).
 */
@Entity
@Table(name = "symbol_strategy", uniqueConstraints = [UniqueConstraint(columnNames = ["market", "symbol"])])
class SymbolStrategy(
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val market: Market,
    @Column(nullable = false, length = 20)
    val symbol: String,
    /** false 면 분봉만 모으고 매수는 안 한다. 매수 종목 선택 스위치. */
    @Column(name = "auto_trade", nullable = false)
    var autoTrade: Boolean,
    @Column(name = "take_profit_percent", nullable = false, precision = 10, scale = 4)
    var takeProfitPercent: BigDecimal,
    @Column(name = "stop_loss_percent", precision = 10, scale = 4)
    var stopLossPercent: BigDecimal?,
    /** true 면 보유 중에도 마틴게일 규칙(MartingaleRule)으로 추가 매수·재진입한다. 아래 martingale_* 는 이때만 쓴다. */
    @Column(nullable = false)
    var martingale: Boolean,
    @Column(name = "martingale_drop_percent", nullable = false, precision = 10, scale = 4)
    var martingaleDropPercent: BigDecimal,
    @Column(name = "martingale_multiplier", nullable = false)
    var martingaleMultiplier: Int,
    @Column(name = "martingale_max_stages", nullable = false)
    var martingaleMaxStages: Int,
    @Column(name = "martingale_final_stage_stop_loss_percent", nullable = false, precision = 10, scale = 4)
    var martingaleFinalStageStopLossPercent: BigDecimal,
    @Column(name = "martingale_reentry_drop_percent", nullable = false, precision = 10, scale = 4)
    var martingaleReentryDropPercent: BigDecimal,
    @Column(name = "martingale_stop_reentry_drop_percent", nullable = false, precision = 10, scale = 4)
    var martingaleStopReentryDropPercent: BigDecimal,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    fun martingaleProperties() =
        MartingaleProperties(
            dropPercent = martingaleDropPercent,
            reentryDropPercent = martingaleReentryDropPercent,
            stopReentryDropPercent = martingaleStopReentryDropPercent,
            multiplier = martingaleMultiplier,
            maxStages = martingaleMaxStages,
            finalStageStopLossPercent = martingaleFinalStageStopLossPercent,
        )
}

/** 설정 화면 입력값. 비어 있는 칸도 받아서 [SymbolStrategyService.update] 가 검증 메시지로 돌려준다. */
class SymbolStrategyForm {
    var autoTrade: Boolean = false
    var takeProfitPercent: BigDecimal? = null
    var stopLossPercent: BigDecimal? = null
    var martingale: Boolean = false
    var martingaleDropPercent: BigDecimal? = null
    var martingaleMultiplier: Int? = null
    var martingaleMaxStages: Int? = null
    var martingaleFinalStageStopLossPercent: BigDecimal? = null
    var martingaleReentryDropPercent: BigDecimal? = null
    var martingaleStopReentryDropPercent: BigDecimal? = null
}

interface SymbolStrategyRepository : JpaRepository<SymbolStrategy, Long> {
    fun findByMarketAndSymbol(
        market: Market,
        symbol: String,
    ): SymbolStrategy?
}

@Service
class SymbolStrategyService(
    private val repository: SymbolStrategyRepository,
    private val strategyProperties: StrategyProperties,
    private val martingaleProperties: MartingaleProperties,
) {
    fun find(
        market: Market,
        symbol: String,
    ): SymbolStrategy? = repository.findByMarketAndSymbol(market, symbol)

    /** 화면용: 감시 종목 순서대로, 설정 행이 있는 종목만. */
    fun all(): List<Pair<WatchedSymbol, SymbolStrategy>> =
        WatchedSymbol.entries.mapNotNull { w -> find(w.market, w.symbol)?.let { w to it } }

    /** 값이 잘못됐으면 [IllegalArgumentException] (메시지는 화면에 그대로 보여준다). 스케줄러가 매번 읽으므로 저장 즉시 반영된다. */
    @Transactional
    fun update(
        market: Market,
        symbol: String,
        form: SymbolStrategyForm,
    ) {
        val target = requireNotNull(find(market, symbol)) { "설정이 없는 종목입니다: $symbol" }
        val takeProfit = positive(form.takeProfitPercent, "익절 %")
        val stopLoss = form.stopLossPercent?.also { positive(it, "손절 %") }
        target.autoTrade = form.autoTrade
        target.takeProfitPercent = takeProfit
        target.stopLossPercent = stopLoss
        target.martingale = form.martingale
        target.martingaleDropPercent = positive(form.martingaleDropPercent, "마틴게일 추가매수 하락 %")
        target.martingaleMultiplier = atLeast(form.martingaleMultiplier, 2, "마틴게일 배수")
        target.martingaleMaxStages = atLeast(form.martingaleMaxStages, 1, "마틴게일 최대 단계")
        target.martingaleFinalStageStopLossPercent = positive(form.martingaleFinalStageStopLossPercent, "마지막 단계 손절 %")
        target.martingaleReentryDropPercent = positive(form.martingaleReentryDropPercent, "익절 뒤 재진입 하락 %")
        target.martingaleStopReentryDropPercent = positive(form.martingaleStopReentryDropPercent, "손절 뒤 재진입 하락 %")
    }

    private fun atLeast(
        value: Int?,
        min: Int,
        name: String,
    ): Int {
        require(value != null && value >= min) { "$name 는 $min 이상의 정수여야 합니다" }
        return value
    }

    private fun positive(
        value: BigDecimal?,
        name: String,
    ): BigDecimal {
        require(value != null && value > BigDecimal.ZERO) { "$name 는 0보다 큰 숫자여야 합니다" }
        return value
    }

    /**
     * 없는 종목 행만 기본값으로 채운다. 기본값은 application.yml 의 quantlog.strategy.* 이고,
     * 자동 매수·마틴게일은 [WatchedSymbol.tradeByDefault] 종목만 켠다(2026-09-30 KODEX 코스닥150레버리지만). 이미 있는 행은 건드리지 않는다.
     */
    fun seedMissing() {
        WatchedSymbol.entries
            .filter { repository.findByMarketAndSymbol(it.market, it.symbol) == null }
            .forEach {
                val trade = it.tradeByDefault
                repository.save(
                    SymbolStrategy(
                        market = it.market,
                        symbol = it.symbol,
                        autoTrade = trade,
                        takeProfitPercent = strategyProperties.takeProfitPercent,
                        stopLossPercent = strategyProperties.stopLossPercent,
                        martingale = trade,
                        martingaleDropPercent = martingaleProperties.dropPercent,
                        martingaleMultiplier = martingaleProperties.multiplier,
                        martingaleMaxStages = martingaleProperties.maxStages,
                        martingaleFinalStageStopLossPercent = martingaleProperties.finalStageStopLossPercent,
                        martingaleReentryDropPercent = martingaleProperties.reentryDropPercent,
                        martingaleStopReentryDropPercent = martingaleProperties.stopReentryDropPercent,
                    ),
                )
                log.info { "[종목 설정] 기본값으로 생성: ${it.market} ${it.symbol} autoTrade=$trade" }
            }
    }
}

@Component
class SymbolStrategySeeder(
    private val service: SymbolStrategyService,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) = service.seedMissing()
}

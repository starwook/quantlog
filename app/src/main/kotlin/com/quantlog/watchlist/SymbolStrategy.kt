package com.quantlog.watchlist

import com.quantlog.broker.Market
import com.quantlog.gatewayclient.WatchSymbolRow
import com.quantlog.gatewayclient.WatchSymbolRowRepository
import com.quantlog.paper.EtfRegistry
import com.quantlog.position.AccountHoldingRepository
import com.quantlog.strategy.MartingaleProperties
import com.quantlog.strategy.StrategyProperties
import mu.KotlinLogging
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

private val log = KotlinLogging.logger {}

/** 행의 마틴게일 값을 규칙 계산용 설정으로 바꾼다. 규칙(strategy)은 앱에만 있어서 엔티티가 아니라 앱의 확장 함수다. */
fun SymbolStrategy.martingaleProperties() =
    MartingaleProperties(
        dropPercent = martingaleDropPercent,
        multiplier = martingaleMultiplier,
        maxStages = martingaleMaxStages,
    )

/** 설정 화면 입력값. 비어 있는 칸도 받아서 [SymbolStrategyService.update] 가 검증 메시지로 돌려준다. */
class SymbolStrategyForm {
    var takeProfitPercent: BigDecimal? = null
    var stopLossPercent: BigDecimal? = null
    var martingale: Boolean = false
    var martingaleDropPercent: BigDecimal? = null
    var martingaleMultiplier: Int? = null
    var martingaleMaxStages: Int? = null
    var etf: Boolean = false
    var supportBounceEntry: Boolean = false
    var periodicRebuy: Boolean = false
    var periodicRebuyQuantity: Int? = null
    var periodicRebuyIntervalMinutes: Int? = null
    var supportBounceQuantity: Int? = null
}

/** 감시 종목이 추가·삭제됐다. 게이트웨이 같은 구독자가 커밋 뒤에 따라가게 하려는 신호다(도메인은 누가 듣는지 모른다). */
object WatchSymbolsChanged

@Service
class SymbolStrategyService(
    private val repository: SymbolStrategyRepository,
    private val watchSymbols: WatchSymbolRowRepository,
    private val holdings: AccountHoldingRepository,
    private val strategyProperties: StrategyProperties,
    private val martingaleProperties: MartingaleProperties,
    private val events: ApplicationEventPublisher,
) : EtfRegistry {
    /** 등록 안 된 종목은 일반 주식으로 본다(제세금을 더 보수적으로 계산). */
    override fun isEtf(
        market: Market,
        symbol: String,
    ): Boolean = find(market, symbol)?.etf ?: false

    fun find(
        market: Market,
        symbol: String,
    ): SymbolStrategy? = repository.findByMarketAndSymbol(market, symbol)

    /** 감시 종목 전체(등록 순서). */
    fun all(): List<SymbolStrategy> = repository.findAll().sortedBy { it.id }

    /** 등록 안 된 종목이면(직접 URL로 들어온 경우, 앱에서 산 종목 등) 코드를 그대로 이름처럼 보여준다. */
    fun displayName(
        market: Market,
        symbol: String,
    ): String = find(market, symbol)?.displayName?.takeIf { it.isNotBlank() } ?: symbol

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
        // 마틴게일은 단계마다 평단이 -dropPercent 씩 내려가 추가 매수하는데, 손절이 그보다 작으면 첫 추가 매수 전에 손절돼 마틴게일이 성립하지 않는다.
        if (form.martingale && stopLoss != null) {
            val drop = positive(form.martingaleDropPercent, "마틴게일 추가매수 하락 %")
            require(stopLoss >= drop) {
                val stopText = stopLoss.stripTrailingZeros().toPlainString()
                "마틴게일을 켠 종목은 손절 %($stopText)가 추가매수 하락 %(${drop.stripTrailingZeros().toPlainString()}) 이상이어야 합니다"
            }
        }
        target.watchSymbol?.etf = form.etf
        target.takeProfitPercent = takeProfit
        target.stopLossPercent = stopLoss
        target.martingale = form.martingale
        target.supportBounceEntry = form.supportBounceEntry
        target.periodicRebuy = form.periodicRebuy
        target.periodicRebuyQuantity = atLeast(form.periodicRebuyQuantity, 1, "재매수 수량")
        target.periodicRebuyIntervalMinutes = atLeast(form.periodicRebuyIntervalMinutes, 1, "재매수 간격(분)")
        target.supportBounceQuantity = atLeast(form.supportBounceQuantity, 1, "저점 판단 진입 수량")
        target.martingaleDropPercent = positive(form.martingaleDropPercent, "마틴게일 추가매수 하락 %")
        target.martingaleMultiplier = atLeast(form.martingaleMultiplier, 2, "마틴게일 배수")
        target.martingaleMaxStages = atLeast(form.martingaleMaxStages, 1, "마틴게일 최대 단계")
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
     * 감시 종목을 새로 등록한다. 값은 application.yml 기본값이고 매수 옵션(마틴게일·주기 재매수·저점 판단 진입)은 모두 꺼진 채로 시작한다 — 화면에서 켠다.
     * 입력이 잘못됐거나 이미 있는 종목이면 [IllegalArgumentException] (메시지는 화면에 그대로 보여준다).
     */
    @Transactional
    fun add(
        market: Market,
        symbol: String,
        displayName: String,
        etf: Boolean = false,
    ) {
        val code = symbol.trim().uppercase()
        require(code.isNotEmpty()) { "종목 코드를 입력해 주세요" }
        require(displayName.isNotBlank()) { "종목 이름을 입력해 주세요" }
        require(find(market, code) == null) { "이미 등록된 종목입니다: $code" }
        repository.save(defaultRow(market, code, displayName.trim(), trade = false, etf = etf))
        events.publishEvent(WatchSymbolsChanged)
    }

    /** 감시 종목을 지운다. 종목 원본(`watch_symbol`)도 같은 트랜잭션에서 지운다. 보유 중인 종목은 지울 수 없다(청산 감시·실시간 구독이 관심종목에 의존한다). */
    @Transactional
    fun remove(
        market: Market,
        symbol: String,
    ) {
        val target = requireNotNull(find(market, symbol)) { "등록되지 않은 종목입니다: $symbol" }
        require(
            holdings.findByMarketAndSymbol(market, symbol)?.quantity?.let { it > 0 } != true,
        ) { "보유 중인 종목은 삭제할 수 없습니다: $symbol (먼저 매도하세요)" }
        repository.delete(target)
        repository.flush()
        target.watchSymbol?.let(watchSymbols::delete)
        events.publishEvent(WatchSymbolsChanged)
    }

    /**
     * `watch_symbol` 연결이 없는 기존 행에 연결한다. 이미 같은 종목의 `watch_symbol` 행이 있으면(옛 동기화가 써 둔 것) 그걸 쓰고, 없으면 옛 ETF 값으로 만든다.
     * 반복 실행해도 안전하다.
     */
    @Transactional
    fun linkWatchSymbols() {
        repository.findAll().filter { it.watchSymbol == null }.forEach { row ->
            row.watchSymbol = watchSymbolOf(row.market, row.symbol, row.legacyEtf)
        }
    }

    /** 같은 종목의 `watch_symbol` 행이 이미 있으면 그걸, 없으면 새로 만든다(unique 키 충돌 방지). */
    private fun watchSymbolOf(
        market: Market,
        symbol: String,
        etf: Boolean,
    ): WatchSymbolRow =
        watchSymbols.findByMarketAndSymbol(market.name, symbol) ?: watchSymbols.save(WatchSymbolRow(market.name, symbol, etf))

    /**
     * 없는 종목 행만 기본값으로 채운다. 기본값은 application.yml 의 quantlog.strategy.* 이고,
     * 마틴게일·저점 판단 진입은 [SeedSymbol.tradeByDefault] 종목만 켠다(2026-09-30 KODEX 코스닥150레버리지만). 이미 있는 행은 건드리지 않는다.
     */
    @Transactional
    fun seedMissing() {
        // ETF 여부는 종목의 사실이라, ETF 컬럼이 생기기 전에 만들어진 행도 시드 기준으로 맞춘다(켜기만 하고 끄지는 않는다).
        SeedSymbol.entries.filter { it.etf }.forEach { seed ->
            repository.findByMarketAndSymbol(seed.market, seed.symbol)?.watchSymbol?.etf = true
        }
        // 이름 컬럼이 생기기 전에 만들어진 행은 이름이 비어 있다 — 시드 이름으로 채운다.
        SeedSymbol.entries.forEach { seed ->
            repository.findByMarketAndSymbol(seed.market, seed.symbol)?.takeIf { it.displayName.isBlank() }?.displayName = seed.displayName
        }
        SeedSymbol.entries
            .filter { repository.findByMarketAndSymbol(it.market, it.symbol) == null }
            .forEach {
                repository.save(defaultRow(it.market, it.symbol, it.displayName, it.tradeByDefault, it.etf))
                log.info { "[종목 설정] 기본값으로 생성: ${it.market} ${it.symbol} 매수 옵션 기본 ${it.tradeByDefault}" }
            }
    }

    private fun defaultRow(
        market: Market,
        symbol: String,
        displayName: String,
        trade: Boolean,
        etf: Boolean,
    ) = SymbolStrategy(
        market = market,
        symbol = symbol,
        displayName = displayName,
        takeProfitPercent = strategyProperties.takeProfitPercent,
        stopLossPercent = strategyProperties.stopLossPercent,
        martingale = trade,
        martingaleDropPercent = martingaleProperties.dropPercent,
        martingaleMultiplier = martingaleProperties.multiplier,
        martingaleMaxStages = martingaleProperties.maxStages,
        legacyEtf = etf,
        supportBounceEntry = trade,
        watchSymbol = watchSymbolOf(market, symbol, etf),
    )
}

@Component
class SymbolStrategySeeder(
    private val service: SymbolStrategyService,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        // 연결은 별도 트랜잭션이다. 읽을 수 없는 옛 행(지원 안 하는 시장 등)이 있어도 앱 기동을 막지 않고 오류만 남긴다.
        runCatching { service.linkWatchSymbols() }
            .onFailure { log.error(it) { "[종목 설정] watch_symbol 연결 실패 — 연결 안 된 종목은 ETF 설정이 반영되지 않는다: ${it.message}" } }
        service.seedMissing()
    }
}

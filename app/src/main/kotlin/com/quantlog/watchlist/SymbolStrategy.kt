package com.quantlog.watchlist

import com.quantlog.broker.Market
import com.quantlog.gatewayclient.WatchSymbolRowRepository
import com.quantlog.paper.EtfRegistry
import com.quantlog.position.AccountHoldingRepository
import com.quantlog.strategy.MartingaleProperties
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** 행의 마틴게일 값을 규칙 계산용 설정으로 바꾼다. 규칙(strategy)은 앱에만 있어서 엔티티가 아니라 앱의 확장 함수다. */
fun SymbolStrategy.martingaleProperties() =
    MartingaleProperties(
        dropPercent = martingaleDropPercent,
        multiplier = martingaleMultiplier,
        maxStages = martingaleMaxStages,
    )

/** 감시 종목이 추가·삭제됐다. 게이트웨이 같은 구독자가 커밋 뒤에 따라가게 하려는 신호다(도메인은 누가 듣는지 모른다). */
object WatchSymbolsChanged

@Service
class SymbolStrategyService(
    private val repository: SymbolStrategyRepository,
    private val watchSymbols: WatchSymbolRowRepository,
    private val holdings: AccountHoldingRepository,
    private val defaults: SymbolStrategyDefaults,
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
        form.applyTo(target)
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
        repository.save(defaults.newRow(market, code, displayName.trim(), trade = false, etf = etf))
        events.publishEvent(WatchSymbolsChanged)
    }

    /** 감시 종목을 지운다. 종목 원본(`watch_symbol`)도 같은 트랜잭션에서 지운다. 보유 중인 종목은 지울 수 없다(청산 감시·실시간 구독이 관심종목에 의존한다). */
    @Transactional
    fun remove(
        market: Market,
        symbol: String,
    ) {
        val target = requireNotNull(find(market, symbol)) { "등록되지 않은 종목입니다: $symbol" }
        val held = (holdings.findByMarketAndSymbol(market, symbol)?.quantity ?: 0) > 0
        require(!held) { "보유 중인 종목은 삭제할 수 없습니다: $symbol (먼저 매도하세요)" }
        repository.delete(target)
        repository.flush()
        target.watchSymbol?.let(watchSymbols::delete)
        events.publishEvent(WatchSymbolsChanged)
    }
}

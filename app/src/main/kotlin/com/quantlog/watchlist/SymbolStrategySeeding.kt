package com.quantlog.watchlist

import mu.KotlinLogging
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

private val log = KotlinLogging.logger {}

/** 기동할 때 종목 설정 행을 맞춘다. 트랜잭션은 [SymbolStrategySeeder] 와 분리된 이 빈의 메서드마다 따로 걸린다. */
@Service
class SymbolStrategySeedService(
    private val repository: SymbolStrategyRepository,
    private val defaults: SymbolStrategyDefaults,
) {
    /**
     * `watch_symbol` 연결이 없는 기존 행에 연결한다. 이미 같은 종목의 `watch_symbol` 행이 있으면(옛 동기화가 써 둔 것) 그걸 쓰고, 없으면 옛 ETF 값으로 만든다.
     * 반복 실행해도 안전하다.
     */
    @Transactional
    fun linkWatchSymbols() {
        repository.findAll().filter { it.watchSymbol == null }.forEach { row ->
            row.watchSymbol = defaults.watchSymbolOf(row.market, row.symbol, row.legacyEtf)
        }
    }

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
                repository.save(defaults.newRow(it.market, it.symbol, it.displayName, it.tradeByDefault, it.etf))
                log.info { "[종목 설정] 기본값으로 생성: ${it.market} ${it.symbol} 매수 옵션 기본 ${it.tradeByDefault}" }
            }
    }
}

@Component
class SymbolStrategySeeder(
    private val service: SymbolStrategySeedService,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        // 연결은 별도 트랜잭션이다. 읽을 수 없는 옛 행(지원 안 하는 시장 등)이 있어도 앱 기동을 막지 않고 오류만 남긴다.
        runCatching { service.linkWatchSymbols() }
            .onFailure { log.error(it) { "[종목 설정] watch_symbol 연결 실패 — 연결 안 된 종목은 ETF 설정이 반영되지 않는다: ${it.message}" } }
        service.seedMissing()
    }
}

package com.quantlog.gateway.record

import com.quantlog.gateway.GatewayProperties
import com.quantlog.gateway.broker.BrokerClient
import com.quantlog.gateway.broker.Market
import com.quantlog.gateway.marketdata.MinuteCandleStore
import mu.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

private val log = KotlinLogging.logger {}
private val KST: ZoneId = ZoneId.of("Asia/Seoul")

/**
 * 관심종목의 분봉을 REST 로 받아 DB 에 쌓는다. KIS 는 당일 분봉만·1회 최대 30건을 주므로(docs/kis-api/README.md) 호출할 때마다 최근 30건을 받아
 * 새로 생긴 것만 저장한다. 예전엔 앱의 진입 스케줄러가 매 주기 같이 했지만, 외부 호출은 이 서버가 맡는다. 주문을 내지 않는 조회·기록뿐이다.
 */
@Component
class CandleCollector(
    private val broker: BrokerClient,
    private val store: MinuteCandleStore,
    private val watchSymbols: WatchSymbolRepository,
    private val properties: GatewayProperties,
) {
    @Scheduled(
        fixedDelayString = "\${quantlog.gateway.candle-collection.interval-millis:1000}",
        initialDelayString = "\${quantlog.gateway.candle-collection.initial-delay-millis:20000}",
    )
    fun run() {
        if (properties.candleCollection.enabled) collect(ZonedDateTime.now(KST))
    }

    fun collect(now: ZonedDateTime) {
        watchSymbols.findAll().forEach { watched ->
            val market = Market.entries.firstOrNull { it.name == watched.market } ?: return@forEach
            if (!market.isTradable(now)) return@forEach
            runCatching {
                val candles = broker.minuteCandles(market, watched.symbol, LocalTime.now(KST))
                val saved = candles.mapNotNull { store.saveIfNew(market, watched.symbol, it) }
                log.info { "[분봉 저장] $market ${watched.symbol}: 조회 ${candles.size}건 중 신규 ${saved.size}건 저장" }
            }.onFailure { log.warn(it) { "[분봉 수집] 실패: ${watched.market} ${watched.symbol}" } }
        }
    }
}

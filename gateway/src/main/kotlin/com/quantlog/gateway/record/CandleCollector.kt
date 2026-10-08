package com.quantlog.gateway.record

import com.fasterxml.jackson.databind.ObjectMapper
import com.quantlog.gateway.GatewayProperties
import com.quantlog.gateway.broker.Market
import com.quantlog.gateway.kis.KisApiClient
import com.quantlog.gateway.kis.KisProperties
import mu.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

private val log = KotlinLogging.logger {}
private val KST: ZoneId = ZoneId.of("Asia/Seoul")

/**
 * 관심종목의 분봉을 REST(FHKST03010200)로 받아 응답 원문을 [KisMinuteChart] 에 쌓는다. 한투는 당일 분봉만·1회 최대 30건을 주므로(docs/kis-api/README.md)
 * 호출할 때마다 최근 30건이 온다 — 어떤 분봉이 새것인지는 앱이 가린다. 직전에 쌓은 것과 본문이 똑같으면(거래가 없어 안 변함) 쌓지 않는다.
 * 하루 지난 줄은 지운다. 주문을 내지 않는 조회·기록뿐이다.
 */
@Component
class CandleCollector(
    private val api: KisApiClient,
    private val kis: KisProperties,
    private val objectMapper: ObjectMapper,
    private val charts: KisMinuteChartRepository,
    private val watchSymbols: WatchSymbolRepository,
    private val properties: GatewayProperties,
) {
    private val lastBody = ConcurrentHashMap<String, String>()
    private var lastPrunedAt: Instant = Instant.EPOCH

    @Scheduled(
        fixedDelayString = "\${quantlog.gateway.candle-collection.interval-millis:1000}",
        initialDelayString = "\${quantlog.gateway.candle-collection.initial-delay-millis:20000}",
    )
    fun run() {
        if (properties.candleCollection.enabled && kis.hasCredentials) collect(ZonedDateTime.now(KST))
    }

    fun collect(now: ZonedDateTime) {
        watchSymbols.findAll().forEach { watched ->
            val market = Market.entries.firstOrNull { it.name == watched.market } ?: return@forEach
            if (!market.isTradable(now)) return@forEach
            runCatching { collectOne(market, watched.symbol, now) }
                .onFailure { log.warn(it) { "[분봉 수집] 실패: ${watched.market} ${watched.symbol}" } }
        }
        prune(now.toInstant())
    }

    private fun collectOne(
        market: Market,
        symbol: String,
        now: ZonedDateTime,
    ) {
        val response =
            api.raw(
                PATH,
                TR_ID,
                KisApiClient.GET,
                mapOf(
                    "FID_COND_MRKT_DIV_CODE" to "J",
                    "FID_INPUT_ISCD" to symbol,
                    "FID_INPUT_HOUR_1" to now.format(HOUR_FORMAT),
                    "FID_PW_DATA_INCU_YN" to "Y",
                    "FID_ETC_CLS_CODE" to "",
                ),
                null,
                null,
            )
        val rtCd = runCatching { objectMapper.readTree(response.body).path("rt_cd").asText() }.getOrNull()
        if (rtCd != "0") {
            log.warn { "[분봉 수집] 한투가 오류로 답함: $market $symbol HTTP ${response.status} ${response.body.take(200)}" }
            return
        }
        val key = "${market.name}:$symbol"
        if (lastBody.put(key, response.body) == response.body) return
        charts.save(KisMinuteChart(market.name, symbol, now.toInstant(), response.body))
    }

    private fun prune(now: Instant) {
        if (Duration.between(lastPrunedAt, now) < PRUNE_EVERY) return
        lastPrunedAt = now
        runCatching { charts.deleteByReceivedAtBefore(now.minus(RETENTION)) }
            .onFailure { log.warn(it) { "[분봉 수집] 오래된 원문을 못 지웠다" } }
    }

    private companion object {
        const val PATH = "/uapi/domestic-stock/v1/quotations/inquire-time-itemchartprice"
        const val TR_ID = "FHKST03010200"
        val HOUR_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HHmmss")
        val RETENTION: Duration = Duration.ofDays(1)
        val PRUNE_EVERY: Duration = Duration.ofHours(1)
    }
}

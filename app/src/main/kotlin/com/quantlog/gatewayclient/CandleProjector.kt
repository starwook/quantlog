package com.quantlog.gatewayclient

import com.fasterxml.jackson.databind.ObjectMapper
import com.quantlog.broker.KisMinuteChartParser
import com.quantlog.broker.Market
import com.quantlog.marketdata.MinuteCandleStore
import mu.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

private val log = KotlinLogging.logger {}

/**
 * 게이트웨이가 원문 그대로 쌓은 분봉 응답(`kis_minute_chart`)을 한투 문서대로 읽어([KisMinuteChartParser]) 앱의 `minute_candle` 에 넣는다.
 * 줄마다 그 종목의 최근 30건이 들어 있어 한 번에 읽은 줄 중 종목별 **가장 최근 줄**만 풀어도 빠지는 분봉이 없다. 이미 있는 분봉은 저장하지 않는다(실시간 합성분과 겹쳐도 된다).
 */
@Component
class CandleProjector(
    private val charts: KisMinuteChartRowRepository,
    private val cursors: ProjectionCursorRepository,
    private val store: MinuteCandleStore,
    private val objectMapper: ObjectMapper,
    private val properties: GatewayClientProperties,
) {
    @Scheduled(fixedDelay = 1_000, initialDelay = 10_000)
    fun run() {
        if (!properties.enabled) return
        runCatching { project() }.onFailure { log.warn(it) { "[분봉 반영] 원문 읽기 실패, 다음 회차에 재시도: ${it.message}" } }
    }

    /** 처리한 줄 수를 돌려준다. */
    @Synchronized
    fun project(): Int {
        var last = cursors.findById(CURSOR).map { it.lastId }.orElse(0)
        var processed = 0
        while (true) {
            val rows = charts.findTop200ByIdGreaterThanOrderByIdAsc(last)
            if (rows.isEmpty()) return processed
            rows.groupBy { it.market to it.symbol }.values.map { it.last() }.forEach { row ->
                runCatching {
                    val market = Market.valueOf(row.market)
                    KisMinuteChartParser.parse(objectMapper.readTree(row.body)).forEach { store.saveIfNew(market, row.symbol, it) }
                }.onFailure { log.error(it) { "[분봉 해석 실패] 건너뛴다: kis_minute_chart.id=${row.id} ${row.body.take(300)}" } }
            }
            last = rows.last().id!!
            processed += rows.size
            cursors.save(ProjectionCursor(CURSOR, last))
        }
    }

    internal companion object {
        const val CURSOR = "kis_minute_chart"
    }
}

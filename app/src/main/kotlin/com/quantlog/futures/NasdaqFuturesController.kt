package com.quantlog.futures

import mu.KotlinLogging
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody

private val log = KotlinLogging.logger {}

private const val POLL_CANDLES = 60

/** 나스닥100 선물 화면: 야후 시세(약 10분 지연)로 그린 1분봉·일봉 차트. 조회가 실패해도 화면은 열린다. */
@Controller
class NasdaqFuturesController(private val client: NasdaqFuturesClient) {
    @GetMapping("/futures/nasdaq")
    fun page(
        @RequestParam(required = false, defaultValue = "M") chart: String,
        model: Model,
    ): String {
        val daily = chart == "D"
        model.addAttribute("chartUnit", if (daily) "D" else "M")
        runCatching { client.quote(daily) }
            .onSuccess { quote ->
                if (quote.candles.isEmpty()) return@onSuccess
                val candleChart = CandleChart(quote.candles, emptyList())
                model.addAttribute("candleChart", candleChart)
                // 기준은 전일 종가다: 1분봉은 야후가 준 값, 일봉은 직전 봉 종가. (CandleChart 의 주간·야간 기준은 코스피200 시간표용이라 쓰지 않는다)
                model.addAttribute("baseline", if (daily) candleChart.baseline else quote.previousClose?.toDouble())
            }
            .onFailure {
                log.warn { "[나스닥선물] 야후 조회 실패: ${it.message}" }
                model.addAttribute("chartError", it.message ?: it.javaClass.simpleName)
            }
        return "nasdaq-futures"
    }

    /** 1분봉 차트가 주기적으로 불러 마지막 봉을 갱신하는 최근 봉. 실패하면 빈 목록이라 화면은 다음 주기에 다시 시도한다. */
    @GetMapping("/futures/nasdaq/candles")
    @ResponseBody
    fun candles(): List<ChartPoint> =
        runCatching { client.quote(false).candles.takeLast(POLL_CANDLES) }
            .onFailure { log.warn { "[나스닥선물] 봉 갱신 실패: ${it.message}" } }
            .getOrDefault(emptyList())
            .let { if (it.isEmpty()) emptyList() else CandleChart(it, emptyList()).points }
}

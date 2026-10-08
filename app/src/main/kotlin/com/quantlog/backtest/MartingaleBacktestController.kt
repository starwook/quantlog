package com.quantlog.backtest

import com.quantlog.broker.Market
import com.quantlog.marketdata.MarketDataService
import com.quantlog.strategy.MartingaleProperties
import com.quantlog.strategy.StrategyProperties
import com.quantlog.watchlist.SymbolStrategyService
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

private val KST: ZoneId = ZoneId.of("Asia/Seoul")

/** 날짜별·조합별 비교는 최근 이 날짜 수까지만 돌린다(조합 수 × 날짜 수 × 분봉 수만큼 계산하므로). */
private const val MAX_DAYS = 30
private const val SWEEP_TOP = 10

private fun comboLabel(
    dropPercent: BigDecimal,
    takeProfitPercent: BigDecimal,
    maxStages: Int,
): String = "간격 ${dropPercent.toPlainString()}% · 익절 ${takeProfitPercent.toPlainString()}% · ${maxStages}단계"

data class MartingaleCycleView(
    val time: String,
    val stages: Int,
    val peakQuantity: String,
    val averagePrice: String,
    val exitPrice: String,
    val pnlText: String,
    val pnlCss: String,
    val maxInvested: String,
    val exitReason: String,
)

data class MartingaleDayRowView(
    val date: String,
    val rangeText: String,
    val pnlText: String,
    val pnlCss: String,
    val maxStage: Int,
    val bestText: String,
    val bestPnlText: String,
    val selected: Boolean,
)

data class MartingaleSweepRowView(
    val label: String,
    val totalText: String,
    val totalCss: String,
    val returnText: String,
    val worstDayText: String,
    val maxInvested: String,
    val winDaysText: String,
)

/**
 * 마틴게일 백테스트 화면. 간격·익절·손절·최대 단계를 바꿔 가며 "이 값이면 그날(그리고 쌓인 모든 날) 얼마였을까"를 본다.
 * 값 기본은 그 종목의 실제 설정(symbol_strategy)이라 화면에서 만지는 건 저장되지 않는다(조회만).
 * 최적 조합은 과거 데이터에 맞춘 사후 결과라 참고용이다(기획서: 백테스트는 과최적화 위험).
 */
@Controller
class MartingaleBacktestController(
    private val marketDataService: MarketDataService,
    private val backtestService: MartingaleBacktestService,
    private val symbolStrategyService: SymbolStrategyService,
    private val martingaleProperties: MartingaleProperties,
    private val strategyProperties: StrategyProperties,
) {
    @GetMapping("/backtest/martingale/{market}/{symbol}")
    fun page(
        @PathVariable market: Market,
        @PathVariable symbol: String,
        @RequestParam query: Map<String, String>,
        model: Model,
    ): String {
        val currency = market.currency
        val params = paramsFrom(market, symbol, query)
        val allDates = marketDataService.candleDates(market, symbol)
        val date = query["date"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: allDates.firstOrNull() ?: LocalDate.now(KST)
        val candlesByDate = allDates.take(MAX_DAYS).associateWith { marketDataService.recentCandles(market, symbol, it) }
        val selectedCandles = candlesByDate[date] ?: marketDataService.recentCandles(market, symbol, date)
        val day = backtestService.simulate(selectedCandles, params)
        val grid = SweepGrid()
        val rows = backtestService.dayRows(candlesByDate, params, grid)
        val sweep = backtestService.sweep(candlesByDate, params, grid)

        model.addAttribute("market", market.name)
        model.addAttribute("symbol", symbol)
        model.addAttribute("symbolName", symbolStrategyService.displayName(market, symbol))
        model.addAttribute("watchedSymbols", symbolStrategyService.all())
        model.addAttribute("currency", currency)
        model.addAttribute("date", date.toString())
        model.addAttribute("dates", allDates.map { it.toString() })
        model.addAttribute("dropPercent", params.dropPercent.toPlainString())
        model.addAttribute("takeProfitPercent", params.takeProfitPercent.toPlainString())
        model.addAttribute("stopLossPercent", params.stopLossPercent?.toPlainString() ?: "")
        model.addAttribute("multiplier", params.multiplier)
        model.addAttribute("maxStages", params.maxStages)
        model.addAttribute("startQuantity", params.startQuantity)
        model.addAttribute("reenter", params.reenter)
        model.addAttribute("candleCount", day.candleCount)
        model.addAttribute("cycles", day.cycles.map { it.toView(currency) })
        model.addAttribute("pnlText", signedMoney(day.pnl, currency))
        model.addAttribute("pnlCss", pnlCss(day.pnl))
        model.addAttribute("returnText", day.returnPercent?.let { signedPercent(it) } ?: "—")
        model.addAttribute("maxInvestedText", day.maxInvested.money(currency))
        model.addAttribute("maxStageText", "${day.maxStage}단계")
        model.addAttribute("dayRows", rows.map { it.toView(date, currency) })
        model.addAttribute("sweepRows", sweep.take(SWEEP_TOP).map { it.toView(currency) })
        model.addAttribute("sweepDays", candlesByDate.size)
        return "backtest-martingale"
    }

    /** 폼에 값이 없으면 종목의 실제 설정, 그것도 없으면 기본값을 쓴다. 손절칸을 비워 보내면 손절 없음이다. */
    private fun paramsFrom(
        market: Market,
        symbol: String,
        query: Map<String, String>,
    ): MartingaleParams {
        val strategy = symbolStrategyService.find(market, symbol)

        fun decimal(
            key: String,
            fallback: BigDecimal,
        ): BigDecimal = query[key]?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: fallback

        val stop =
            if (query.containsKey("stop")) {
                query["stop"]?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
            } else {
                strategy?.stopLossPercent ?: strategyProperties.stopLossPercent
            }
        return MartingaleParams(
            dropPercent = decimal("drop", strategy?.martingaleDropPercent ?: martingaleProperties.dropPercent),
            multiplier = (query["mult"]?.toIntOrNull() ?: strategy?.martingaleMultiplier ?: martingaleProperties.multiplier).coerceIn(2, 3),
            maxStages = (query["stages"]?.toIntOrNull() ?: strategy?.martingaleMaxStages ?: martingaleProperties.maxStages).coerceIn(1, 10),
            takeProfitPercent = decimal("tp", strategy?.takeProfitPercent ?: strategyProperties.takeProfitPercent),
            stopLossPercent = stop,
            startQuantity = (query["qty"]?.toLongOrNull() ?: 1L).coerceIn(1, 1000),
            // 폼을 한 번이라도 제출했으면 체크박스 값(없으면 꺼짐)을, 처음 열었을 땐 켜짐을 쓴다.
            reenter = if (query.containsKey("submitted")) query.containsKey("reenter") else true,
        )
    }

    private fun MartingaleCycleResult.toView(currency: String) =
        MartingaleCycleView(
            time = "$startTime → $endTime",
            stages = stages,
            peakQuantity = "${peakQuantity}주",
            averagePrice = averagePrice.money(currency),
            exitPrice = exitPrice.money(currency),
            pnlText = signedMoney(pnl, currency),
            pnlCss = pnlCss(pnl),
            maxInvested = maxInvested.money(currency),
            exitReason = exitReason,
        )

    private fun MartingaleDayRow.toView(
        selectedDate: LocalDate,
        currency: String,
    ) = MartingaleDayRowView(
        date = date.toString(),
        rangeText = rangePercent?.let { "$it%" } ?: "—",
        pnlText = signedMoney(result.pnl, currency),
        pnlCss = pnlCss(result.pnl),
        maxStage = result.maxStage,
        bestText = best?.params?.let { comboLabel(it.dropPercent, it.takeProfitPercent, it.maxStages) } ?: "—",
        bestPnlText = best?.let { signedMoney(it.pnl, currency) } ?: "—",
        selected = date == selectedDate,
    )

    private fun MartingaleSweepRow.toView(currency: String) =
        MartingaleSweepRowView(
            label = comboLabel(dropPercent, takeProfitPercent, maxStages),
            totalText = signedMoney(totalPnl, currency),
            totalCss = pnlCss(totalPnl),
            returnText = returnPercent?.let { signedPercent(it) } ?: "—",
            worstDayText = signedMoney(worstDayPnl, currency),
            maxInvested = maxInvested.money(currency),
            winDaysText = "$winDays/${days}일",
        )
}

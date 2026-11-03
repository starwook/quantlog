package com.quantlog.backtest

import com.quantlog.broker.Market
import com.quantlog.common.money
import com.quantlog.common.pnlCss
import com.quantlog.common.signedPercent
import com.quantlog.indicator.IndicatorPreset
import com.quantlog.indicator.PresetParams
import com.quantlog.marketdata.MarketDataService
import com.quantlog.strategy.StrategyProperties
import com.quantlog.watchlist.SymbolStrategyService
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import java.math.BigDecimal
import java.time.LocalDate

data class PresetOptionView(val name: String, val label: String, val selected: Boolean)

data class ParamInputView(val name: String, val label: String, val value: String, val min: String, val max: String, val step: String)

data class IndicatorTradeView(
    val time: String,
    val prices: String,
    val returnText: String,
    val returnCss: String,
    val entryReason: String,
    val exitReason: String,
)

data class IndicatorDayRowView(
    val date: String,
    val returnText: String,
    val returnCss: String,
    val tradesText: String,
    val selected: Boolean,
)

/**
 * 지표 전략 백테스트 화면. 프리셋(한투 10개 + 3개)과 파라미터를 바꿔 "이 전략이면 그날·최근 며칠 어땠을까"를 본다.
 * 익절·손절 기본값은 종목의 실제 설정(symbol_strategy)이고, 화면에서 만지는 값은 저장되지 않는다(조회만).
 */
@Controller
class IndicatorBacktestController(
    private val marketDataService: MarketDataService,
    private val backtestService: IndicatorBacktestService,
    private val symbolStrategyService: SymbolStrategyService,
    private val strategyProperties: StrategyProperties,
) {
    @GetMapping("/backtest/indicator/{market}/{symbol}")
    fun page(
        @PathVariable market: Market,
        @PathVariable symbol: String,
        @RequestParam query: Map<String, String>,
        model: Model,
    ): String {
        val currency = market.currency
        val params = paramsFrom(market, symbol, query)
        // 최신순. 각 날짜의 "직전 거래일"은 목록의 다음 칸이다(판정용으로 앞에 붙인다).
        val allDates = marketDataService.candleDates(market, symbol)
        val recent = allDates.take(MAX_DAYS + 1)
        val candlesByDate = recent.associateWith { marketDataService.recentCandles(market, symbol, it) }
        val days =
            recent.take(MAX_DAYS).mapIndexed { index, date ->
                val previous = recent.getOrNull(index + 1)?.let { candlesByDate.getValue(it) }.orEmpty()
                backtestService.simulateDay(date, previous, candlesByDate.getValue(date), params)
            }
        val date = query["date"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: days.firstOrNull()?.date
        val day = days.firstOrNull { it.date == date }
        val summary = backtestService.summarize(days)

        model.addAttribute("market", market.name)
        model.addAttribute("symbol", symbol)
        model.addAttribute("symbolName", symbolStrategyService.displayName(market, symbol))
        model.addAttribute("watchedSymbols", symbolStrategyService.all())
        model.addAttribute("currency", currency)
        model.addAttribute("date", date?.toString() ?: "")
        model.addAttribute("presetName", params.preset.name)
        model.addAttribute("presetDescription", params.preset.description)
        model.addAttribute("presetOrigin", params.preset.origin)
        model.addAttribute("hasSell", params.preset.hasSell)
        model.addAttribute("presets", IndicatorPreset.entries.map { PresetOptionView(it.name, it.label, it == params.preset) })
        model.addAttribute(
            "paramInputs",
            params.preset.params.map {
                ParamInputView(
                    PARAM_PREFIX + it.key,
                    it.label,
                    params.presetParams.decimal(it.key).stripTrailingZeros().toPlainString(),
                    it.min.toPlainString(),
                    it.max.toPlainString(),
                    it.step.toPlainString(),
                )
            },
        )
        model.addAttribute("takeProfitPercent", params.takeProfitPercent.stripTrailingZeros().toPlainString())
        model.addAttribute("stopLossPercent", params.stopLossPercent?.stripTrailingZeros()?.toPlainString() ?: "")
        model.addAttribute("sellOnSignal", params.sellOnSignal)
        model.addAttribute("dayQuery", dayQuery(params))
        model.addAttribute("candleCount", day?.candleCount ?: 0)
        model.addAttribute("trades", day?.trades.orEmpty().map { it.toView(currency) })
        model.addAttribute("dayReturnText", signedPercent(day?.returnPercent ?: BigDecimal.ZERO))
        model.addAttribute("dayReturnCss", pnlCss(day?.returnPercent ?: BigDecimal.ZERO))
        model.addAttribute("summaryDays", summary.days)
        model.addAttribute("totalReturnText", signedPercent(summary.totalReturnPercent))
        model.addAttribute("totalReturnCss", pnlCss(summary.totalReturnPercent))
        model.addAttribute(
            "maxDrawdownText",
            "-" + summary.maxDrawdownPercent.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() + "%p",
        )
        model.addAttribute("winRateText", summary.winRatePercent?.let { "$it%" } ?: "—")
        model.addAttribute("tradeCountText", "${summary.tradeCount}건 · 수익일 ${summary.winDays}/${summary.days}일")
        model.addAttribute("dayRows", days.map { it.toRow(date) })
        return "backtest-indicator"
    }

    /** 폼 값이 없으면 프리셋 기본값, 익절·손절은 종목의 실제 설정. 손절칸을 비워 보내면 손절 없음. */
    private fun paramsFrom(
        market: Market,
        symbol: String,
        query: Map<String, String>,
    ): IndicatorBacktestParams {
        val strategy = symbolStrategyService.find(market, symbol)
        val preset =
            query["preset"]?.let {
                    name ->
                IndicatorPreset.entries.firstOrNull { it.name == name }
            } ?: IndicatorPreset.GOLDEN_CROSS
        val raw = preset.params.associate { it.key to query[PARAM_PREFIX + it.key]?.toBigDecimalOrNull() }
        val stop =
            if (query.containsKey("stop")) {
                query["stop"]?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
            } else {
                strategy?.stopLossPercent ?: strategyProperties.stopLossPercent
            }
        return IndicatorBacktestParams(
            preset = preset,
            presetParams = PresetParams.of(preset, raw),
            takeProfitPercent =
                query["tp"]?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
                    ?: strategy?.takeProfitPercent ?: strategyProperties.takeProfitPercent,
            stopLossPercent = stop,
            sellOnSignal = preset.hasSell && query.containsKey("sell"),
        )
    }

    /** 날짜 목록의 링크가 지금 값을 그대로 들고 가게 하는 쿼리(날짜 제외). */
    private fun dayQuery(params: IndicatorBacktestParams): String {
        val parts = mutableListOf("preset=${params.preset.name}")
        params.preset.params.forEach { parts += "$PARAM_PREFIX${it.key}=${params.presetParams.decimal(it.key).toPlainString()}" }
        parts += "tp=${params.takeProfitPercent.toPlainString()}"
        parts += "stop=${params.stopLossPercent?.toPlainString() ?: ""}"
        if (params.sellOnSignal) parts += "sell=on"
        return parts.joinToString("&")
    }

    private fun IndicatorTrade.toView(currency: String) =
        IndicatorTradeView(
            time = "$entryTime → $exitTime",
            prices = "${entryPrice.money(currency)} → ${exitPrice.money(currency)}",
            returnText = signedPercent(returnPercent),
            returnCss = pnlCss(returnPercent),
            entryReason = entryReason,
            exitReason = exitReason,
        )

    private fun IndicatorDayResult.toRow(selectedDate: LocalDate?) =
        IndicatorDayRowView(
            date = date.toString(),
            returnText = signedPercent(returnPercent),
            returnCss = pnlCss(returnPercent),
            tradesText = if (candleCount == 0) "분봉 없음" else "거래 ${trades.size}건 · 수익 ${winCount}건",
            selected = date == selectedDate,
        )

    companion object {
        /** 최근 이 날짜 수까지 돌린다(날짜마다 분봉 전체를 판정하므로). */
        const val MAX_DAYS = 20
        const val PARAM_PREFIX = "p_"
    }
}

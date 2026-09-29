package com.quantlog.backtest

import com.quantlog.broker.Market
import com.quantlog.marketdata.MarketDataService
import com.quantlog.watchlist.WatchedSymbol
import com.quantlog.watchlist.displayNameOf
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

private val KST: ZoneId = ZoneId.of("Asia/Seoul")

data class SimulatedTradeView(
    val entryTime: String,
    val entryPrice: String,
    val exitTime: String,
    val exitPrice: String,
    val returnText: String,
    val returnCss: String,
    val exitReason: String,
)

/**
 * 분봉 백테스트 화면. 실제 매매 없이 "이 규칙대로 했으면 그날 얼마 벌었을까"를 계산해서 보여준다.
 * chart 와 마찬가지로 marketdata·strategy 를 조합하는 화면이라 별도 패키지로 뺐다.
 */
@Controller
class BacktestController(
    private val marketDataService: MarketDataService,
    private val backtestService: BacktestService,
) {
    @GetMapping("/backtest/{market}/{symbol}")
    fun page(
        @PathVariable market: Market,
        @PathVariable symbol: String,
        @RequestParam(required = false) date: String?,
        model: Model,
    ): String {
        val targetDate = date?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now(KST)
        val candles = marketDataService.recentCandles(market, symbol, targetDate)
        val result = backtestService.simulate(candles)
        val currency = market.currency

        model.addAttribute("market", market.name)
        model.addAttribute("symbol", symbol)
        model.addAttribute("symbolName", displayNameOf(market, symbol))
        model.addAttribute("date", targetDate.toString())
        model.addAttribute("prevDate", targetDate.minusDays(1).toString())
        model.addAttribute("nextDate", targetDate.plusDays(1).toString())
        model.addAttribute("watchedSymbols", WatchedSymbol.entries)
        model.addAttribute("candleCount", result.candleCount)
        model.addAttribute("hasTrades", result.trades.isNotEmpty())
        model.addAttribute("trades", result.trades.map { it.toView(currency) })
        model.addAttribute("tradeCount", result.trades.size)
        model.addAttribute("winCount", result.winCount)
        model.addAttribute("lossCount", result.lossCount)
        model.addAttribute("winRateText", result.winRatePercent?.let { "$it%" } ?: "—")
        model.addAttribute("totalReturnText", signedPercent(result.totalReturnPercent))
        model.addAttribute("totalReturnCss", pnlCss(result.totalReturnPercent))
        return "backtest"
    }

    private fun SimulatedTrade.toView(currency: String) =
        SimulatedTradeView(
            entryTime = entryTime.toString(),
            entryPrice = entryPrice.money(currency),
            exitTime = exitTime.toString(),
            exitPrice = exitPrice.money(currency),
            returnText = signedPercent(returnPercent),
            returnCss = pnlCss(returnPercent),
            exitReason = exitReason,
        )

    private fun BigDecimal.money(currency: String): String {
        val scale = if (currency == "KRW") 0 else 2
        return String.format(Locale.US, "%,.${scale}f", this)
    }

    private fun signedPercent(value: BigDecimal): String {
        val v = value.setScale(2, RoundingMode.HALF_UP)
        val sign = if (v > BigDecimal.ZERO) "+" else ""
        return "$sign${v.toPlainString()}%"
    }

    private fun pnlCss(value: BigDecimal): String =
        when {
            value > BigDecimal.ZERO -> "pos"
            value < BigDecimal.ZERO -> "neg"
            else -> "zero"
        }
}

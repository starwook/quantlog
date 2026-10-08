package com.quantlog.realized

import com.quantlog.order.pnlCss
import com.quantlog.order.signedMoneyAndPercent
import com.quantlog.position.PortfolioService
import com.quantlog.watchlist.SymbolStrategyService
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/** 화면에 그대로 찍을 수 있게 포맷을 끝낸 값. 템플릿에는 포맷·색상 판정 로직을 두지 않는다. */
data class PnlLineView(
    val market: String,
    val symbol: String,
    val symbolName: String,
    val text: String,
    val css: String,
)

data class DayView(
    val dateText: String,
    val text: String,
    val css: String,
    val lines: List<PnlLineView>,
)

data class CurrencyReportView(
    val currency: String,
    val totalText: String,
    val totalCss: String,
    val days: List<DayView>,
    val symbols: List<PnlLineView>,
)

/** 토스증권처럼 월별 실현손익을 일별·종목별 합계로 보여준다. 데이터는 [PortfolioService] 가 확정한 매도별 손익을 그대로 쓴다. */
@Controller
class RealizedPnlController(
    private val portfolioService: PortfolioService,
    private val symbolStrategyService: SymbolStrategyService,
) {
    @GetMapping("/realized")
    fun realized(
        @RequestParam(required = false) month: String?,
        @RequestParam(defaultValue = "daily") view: String,
        model: Model,
    ): String {
        val thisMonth = YearMonth.now(KST)
        val selected = month?.let { parseMonth(it) } ?: thisMonth
        val reports = RealizedPnlReport.of(portfolioService.snapshot(), selected).map { it.toView() }

        model.addAttribute("monthText", "${selected.monthValue}월")
        model.addAttribute("month", selected.toString())
        model.addAttribute("prevMonth", selected.minusMonths(1).toString())
        model.addAttribute("nextMonth", selected.plusMonths(1).takeIf { it <= thisMonth }?.toString())
        model.addAttribute("view", if (view == "symbol") "symbol" else "daily")
        model.addAttribute("reports", reports)
        return "realized"
    }

    private fun parseMonth(text: String): YearMonth? =
        try {
            YearMonth.parse(text)
        } catch (_: DateTimeParseException) {
            null
        }

    private fun CurrencyRealizedReport.toView() =
        CurrencyReportView(
            currency = currency,
            totalText = total.text(currency),
            totalCss = pnlCss(total.amount),
            days = days.map { it.toView(currency) },
            symbols = symbols.map { it.toView(currency) },
        )

    private fun DayPnl.toView(currency: String) =
        DayView(date.format(DATE_FORMAT), sum.text(currency), pnlCss(sum.amount), symbols.map { it.toView(currency) })

    private fun SymbolPnl.toView(currency: String) =
        PnlLineView(market.name, symbol, symbolStrategyService.displayName(market, symbol), sum.text(currency), pnlCss(sum.amount))

    private fun PnlSum.text(currency: String) = signedMoneyAndPercent(amount, percent, currency)

    private companion object {
        val KST: ZoneId = ZoneId.of("Asia/Seoul")
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREAN)
    }
}

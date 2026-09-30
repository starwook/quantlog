package com.quantlog.position

import com.quantlog.watchlist.WatchedSymbol
import com.quantlog.watchlist.displayNameOf
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 화면에 그대로 찍을 수 있게 미리 포맷을 끝낸 값들. 템플릿에는 포맷·색상 판정 로직을 두지 않는다. */
data class TradeRow(
    val executedAtKst: String,
    val market: String,
    val symbol: String,
    val symbolName: String,
    val side: String,
    val sideCss: String,
    val quantity: Int,
    /** 실제 체결가 우선. 못 구했으면 지정가 + "(체결가 미확인)". */
    val priceText: String,
    val orderNo: String,
    val reason: String,
    val pnlText: String,
    val pnlCss: String,
)

data class HoldingRow(
    val market: String,
    val symbol: String,
    val symbolName: String,
    val quantity: Int,
    val avgCost: String,
    val currentPrice: String,
    val stale: Boolean,
    val valueText: String,
    val pnlText: String,
    val pnlCss: String,
)

data class SummaryView(
    val currency: String,
    val holdingsValueText: String,
    val costBasisText: String,
    val unrealizedText: String,
    val unrealizedCss: String,
    val realizedTodayText: String,
    val realizedTodayCss: String,
    val realizedTotalText: String,
    val realizedTotalCss: String,
    val buyCountTodayText: String,
    val sellCountTodayText: String,
    val holdingCount: Int,
    val holdings: List<HoldingRow>,
)

@Controller
class TradeController(
    private val portfolioService: PortfolioService,
) {
    @GetMapping("/")
    fun trades(model: Model): String {
        val snapshot = portfolioService.accountSnapshot()

        val summaries = snapshot.summaryByCurrency.values.sortedBy { it.currency }.map { it.toView() }
        val rows =
            snapshot.trades
                .sortedByDescending { it.executedAt }
                .map { it.toRow(snapshot.realizedPnlByTradeId[it.id]) }

        model.addAttribute("summaries", summaries)
        model.addAttribute("hasSummaries", summaries.isNotEmpty())
        model.addAttribute("trades", rows)
        model.addAttribute("hasTrades", rows.isNotEmpty())
        model.addAttribute("chartSymbols", WatchedSymbol.entries)
        return "trades"
    }

    private fun Trade.toRow(pnl: RealizedPnl?) =
        TradeRow(
            executedAtKst = TIME_FORMAT.format(executedAt.atZone(KST)),
            market = market.name,
            symbol = symbol,
            symbolName = displayNameOf(market, symbol),
            side = side.name,
            sideCss = side.name.lowercase(),
            quantity = quantity,
            priceText = priceText(),
            orderNo = orderNo,
            reason = reason ?: "—",
            pnlText = pnl?.let { "${it.amount.signedMoney(market.currency)} (${it.percent.percentText()})" } ?: "—",
            pnlCss = pnl?.amount?.pnlCss() ?: "muted",
        )

    /** 체결가를 우선 보여준다. 지정가와 다르면 같이 적고, 체결가를 못 구했으면 그렇다고 밝힌다. */
    private fun Trade.priceText(): String {
        val currency = market.currency
        val filled = filledPrice ?: return "${orderPrice.money(currency)} (지정가, 체결가 미확인)"
        if (filled.compareTo(orderPrice) == 0) return filled.money(currency)
        return "${filled.money(currency)} (지정가 ${orderPrice.money(currency)})"
    }

    private fun PortfolioSummary.toView() =
        SummaryView(
            currency = currency,
            holdingsValueText = holdingsValue.money(currency),
            costBasisText = costBasis.money(currency),
            unrealizedText = "${unrealizedPnl.signedMoney(currency)} (${unrealizedPnlPercent.percentText()})",
            unrealizedCss = unrealizedPnl.pnlCss(),
            realizedTodayText = "${realizedPnlToday.signedMoney(currency)} (${realizedPnlTodayPercent.percentText()})",
            realizedTodayCss = realizedPnlToday.pnlCss(),
            realizedTotalText = "${realizedPnlTotal.signedMoney(currency)} (${realizedPnlTotalPercent.percentText()})",
            realizedTotalCss = realizedPnlTotal.pnlCss(),
            buyCountTodayText = "${buyCountToday}회",
            sellCountTodayText = "${sellCountToday}회",
            holdingCount = holdings.size,
            holdings = holdings.map { it.toRow() },
        )

    private fun HoldingView.toRow() =
        HoldingRow(
            market = market.name,
            symbol = symbol,
            symbolName = displayNameOf(market, symbol),
            quantity = quantity,
            avgCost = avgCost.money(market.currency),
            currentPrice = currentPrice.money(market.currency),
            stale = priceStale,
            valueText = value.money(market.currency),
            pnlText = "${unrealizedPnl.signedMoney(market.currency)} (${unrealizedPnlPercent.percentText()})",
            pnlCss = unrealizedPnl.pnlCss(),
        )

    /**
     * 통화별로 자릿수를 다르게 표기한다 — 원화는 소수점 없이 천 단위 구분, 달러는 소수점 둘째 자리까지.
     * 2026-09-29: 기존엔 setScale(2)+toPlainString() 이라 "1779500.00"처럼 구분자 없이 나와 숫자를
     * 잘못 읽기 쉬웠다(원화는 애초에 소수점이 없는 통화).
     */
    private fun BigDecimal.money(currency: String): String {
        val scale = if (currency == "KRW") 0 else 2
        return String.format(Locale.US, "%,.${scale}f", this)
    }

    /** 손익처럼 부호가 의미 있는 값에 쓴다. 음수는 money() 가 이미 "-"를 붙여주니 양수에만 "+"를 더한다. */
    private fun BigDecimal.signedMoney(currency: String): String {
        val formatted = money(currency)
        return if (this > BigDecimal.ZERO) "+$formatted" else formatted
    }

    private fun BigDecimal.percentText(): String {
        val v = setScale(2, RoundingMode.HALF_UP)
        val sign = if (v > BigDecimal.ZERO) "+" else ""
        return "$sign${v.toPlainString()}%"
    }

    /** 한국 증권가 관행: 이익(상승)=빨강, 손실(하락)=파랑. */
    private fun BigDecimal.pnlCss(): String =
        when {
            this > BigDecimal.ZERO -> "pos"
            this < BigDecimal.ZERO -> "neg"
            else -> "zero"
        }

    private companion object {
        val KST: ZoneId = ZoneId.of("Asia/Seoul")
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}

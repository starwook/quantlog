package com.quantlog.position

import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 화면에 그대로 찍을 수 있게 미리 포맷을 끝낸 값들. 템플릿에는 포맷·색상 판정 로직을 두지 않는다. */
data class TradeRow(
    val executedAtKst: String,
    val market: String,
    val symbol: String,
    val side: String,
    val sideCss: String,
    val quantity: Int,
    val orderPrice: String,
    val orderNo: String,
    val reason: String,
    val pnlText: String,
    val pnlCss: String,
)

data class HoldingRow(
    val market: String,
    val symbol: String,
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
    val holdingCount: Int,
    val holdings: List<HoldingRow>,
)

@Controller
class TradeController(private val portfolioService: PortfolioService) {
    @GetMapping("/")
    fun trades(model: Model): String {
        val snapshot = portfolioService.snapshot()

        val summaries = snapshot.summaryByCurrency.values.sortedBy { it.currency }.map { it.toView() }
        val rows =
            snapshot.trades
                .sortedByDescending { it.executedAt }
                .map { it.toRow(snapshot.realizedPnlByTradeId[it.id]) }

        model.addAttribute("summaries", summaries)
        model.addAttribute("hasSummaries", summaries.isNotEmpty())
        model.addAttribute("trades", rows)
        model.addAttribute("hasTrades", rows.isNotEmpty())
        return "trades"
    }

    private fun Trade.toRow(pnl: RealizedPnl?) =
        TradeRow(
            executedAtKst = TIME_FORMAT.format(executedAt.atZone(KST)),
            market = market.name,
            symbol = symbol,
            side = side.name,
            sideCss = side.name.lowercase(),
            quantity = quantity,
            orderPrice = orderPrice.money(),
            orderNo = orderNo,
            reason = reason ?: "—",
            pnlText = pnl?.let { "${it.amount.money()} (${it.percent.percentText()})" } ?: "—",
            pnlCss = pnl?.amount?.pnlCss() ?: "muted",
        )

    private fun PortfolioSummary.toView() =
        SummaryView(
            currency = currency,
            holdingsValueText = holdingsValue.money(),
            costBasisText = costBasis.money(),
            unrealizedText = "${unrealizedPnl.money()} (${unrealizedPnlPercent.percentText()})",
            unrealizedCss = unrealizedPnl.pnlCss(),
            realizedTodayText = "${realizedPnlToday.money()} (${realizedPnlTodayPercent.percentText()})",
            realizedTodayCss = realizedPnlToday.pnlCss(),
            realizedTotalText = "${realizedPnlTotal.money()} (${realizedPnlTotalPercent.percentText()})",
            realizedTotalCss = realizedPnlTotal.pnlCss(),
            holdingCount = holdings.size,
            holdings = holdings.map { it.toRow() },
        )

    private fun HoldingView.toRow() =
        HoldingRow(
            market = market.name,
            symbol = symbol,
            quantity = quantity,
            avgCost = avgCost.money(),
            currentPrice = currentPrice.money(),
            stale = priceStale,
            valueText = value.money(),
            pnlText = "${unrealizedPnl.money()} (${unrealizedPnlPercent.percentText()})",
            pnlCss = unrealizedPnl.pnlCss(),
        )

    /** 통화 기호까지 붙인 금액 표기. 통화가 섞이지 않도록 항상 currency 와 함께 다닌다. */
    private fun BigDecimal.money(): String = setScale(2, RoundingMode.HALF_UP).toPlainString()

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

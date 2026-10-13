package com.quantlog.futures

import mu.KotlinLogging
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeParseException

private val log = KotlinLogging.logger {}

/**
 * 선물옵션 화면: 지수옵션 매물대(행사가별 미결제약정, 하루 한 번 모은 저장본)와 지수선물 전광판(열 때마다 조회).
 * 다음 날 매매 종목을 고를 때 시장 분위기를 보는 용도.
 */
@Controller
class FuturesController(
    private val client: FuturesOptionsClient,
    private val store: OptionSnapshotStore,
    private val collector: OptionChainCollector,
    private val clock: Clock,
) {
    @GetMapping("/futures")
    fun page(
        @RequestParam(required = false) maturity: String?,
        @RequestParam(required = false, defaultValue = "D") chart: String,
        model: Model,
    ): String {
        val month = month(maturity)
        model.addAttribute("maturity", month.toString())
        model.addAttribute("expiry", OptionMaturity.expiry(month))
        model.addAttribute("collecting", collector.isRunning)
        store.latest(month)?.let { (date, board) ->
            model.addAttribute("snapshotDate", date)
            model.addAttribute("board", board)
        }
        model.addAttribute("chartUnit", chart)
        runCatching { client.futuresBoard() }
            .onSuccess {
                model.addAttribute("futures", it)
                addChart(model, it, chart == "M")
            }
            .onFailure {
                log.warn { "[선물옵션] 선물 전광판 조회 실패: ${it.message}" }
                model.addAttribute("futuresError", it.message)
            }
        return "futures"
    }

    /** 가장 가까운 월물 선물의 캔들 차트. 조회가 실패해도 화면은 그대로 열린다. */
    private fun addChart(
        model: Model,
        futures: List<Map<String, String>>,
        minute: Boolean,
    ) {
        val code = futures.firstOrNull()?.get("futs_shrn_iscd")?.takeIf { it.isNotBlank() } ?: return
        runCatching { client.candles(code, minute, LocalDate.now(clock)) }
            .onSuccess { candles ->
                if (candles.isEmpty()) return@onSuccess
                val levels = (model.getAttribute("board") as? OptionBoard)?.keyLevels.orEmpty()
                model.addAttribute("candleChart", CandleChart(candles, levels))
            }
            .onFailure {
                log.warn { "[선물옵션] 캔들 조회 실패: ${it.message}" }
                model.addAttribute("chartError", it.message)
            }
    }

    /** 매물대를 지금 한 번 모은다. 한투 호출 100번 안팎에 5분쯤 걸려 뒤에서 돌린다. */
    @PostMapping("/futures/collect")
    fun collect(
        @RequestParam(required = false) maturity: String?,
        redirect: RedirectAttributes,
    ): String {
        val month = month(maturity)
        if (collector.collectInBackground(month)) {
            redirect.addFlashAttribute("done", "$month 월물을 모으기 시작했습니다. 5분쯤 뒤 새로고침하세요.")
        } else {
            redirect.addFlashAttribute("error", "이미 모으는 중입니다. 잠시 뒤 새로고침하세요.")
        }
        return "redirect:/futures?maturity=$month"
    }

    private fun month(text: String?): YearMonth =
        try {
            text?.let { YearMonth.parse(it) }
        } catch (e: DateTimeParseException) {
            null
        } ?: OptionMaturity.nearest(LocalDate.now(clock))
}

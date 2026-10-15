package com.quantlog.futures

import mu.KotlinLogging
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

private val log = KotlinLogging.logger {}

private val SNAPSHOT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private const val MAX_MINUTE_CANDLES = 300

private val CODE_FORMAT = Regex("[A-Za-z0-9]{1,12}")

/**
 * 선물옵션 화면: 지수옵션 매물대(행사가별 미결제약정, 하루 한 번 모은 저장본)와 지수선물 전광판(열 때마다 조회).
 * 다음 날 매매 종목을 고를 때 시장 분위기를 보는 용도.
 */
@Controller
class FuturesController(
    private val client: FuturesOptionsClient,
    private val store: OptionSnapshotStore,
    private val collector: OptionChainCollector,
    private val night: NightFuturesRecorder,
    private val clock: Clock,
) {
    @GetMapping("/futures")
    fun page(
        @RequestParam(required = false) maturity: String?,
        @RequestParam(required = false, defaultValue = "M") chart: String,
        model: Model,
    ): String {
        val month = month(maturity)
        model.addAttribute("maturity", month.toString())
        model.addAttribute("expiry", OptionMaturity.expiry(month))
        model.addAttribute("collecting", collector.isRunning)
        store.latest(month)?.let { (date, board) ->
            model.addAttribute("snapshotAt", board.collectedAt?.format(SNAPSHOT_TIME) ?: date.toString())
            model.addAttribute("board", board)
        }
        model.addAttribute("chartUnit", chart)
        runCatching { client.futuresBoard() }
            .onSuccess {
                model.addAttribute("futures", it)
                addChart(model, it, chart)
                (model.getAttribute("board") as? OptionBoard)?.let { board ->
                    val last = (model.getAttribute("candleChart") as? CandleChart)?.candles?.last()?.close
                    model.addAttribute("levels", board.keyLevels(last))
                    model.addAttribute("atmStrike", board.atmStrike(last))
                }
            }
            .onFailure {
                log.warn { "[선물옵션] 선물 전광판 조회 실패: ${it.message}" }
                model.addAttribute("futuresError", it.message)
            }
        return "futures"
    }

    /** 차트가 몇 초마다 불러 마지막 봉을 갱신하는 최근 봉. 조회가 실패하면 빈 목록이라 화면은 다음 주기에 다시 시도한다. */
    @GetMapping("/futures/candles")
    @ResponseBody
    fun candles(
        @RequestParam code: String,
        @RequestParam(defaultValue = "M") chart: String,
    ): List<ChartPoint> {
        if (!CODE_FORMAT.matches(code)) return emptyList()
        return runCatching { candlesOf(code, chart).takeLast(MAX_MINUTE_CANDLES) }
            .onFailure { log.warn { "[선물옵션] 캔들 갱신 실패: ${it.message}" } }
            .getOrDefault(emptyList())
            .let { if (it.isEmpty()) emptyList() else CandleChart(it, emptyList()).points }
    }

    /** 가장 가까운 월물 선물의 캔들 차트. 조회가 실패해도 화면은 그대로 열린다. */
    private fun addChart(
        model: Model,
        futures: List<Map<String, String>>,
        unit: String,
    ) {
        val code = futures.firstOrNull()?.get("futs_shrn_iscd")?.takeIf { it.isNotBlank() } ?: return
        model.addAttribute("futuresCode", code)
        runCatching { candlesOf(code, unit) }
            .onSuccess { candles ->
                if (candles.isEmpty()) return@onSuccess
                val levels = (model.getAttribute("board") as? OptionBoard)?.keyLevels().orEmpty()
                // 기준 종가는 자르기 전 봉에서 구한다: 야간 봉이 300개를 채우면 정규장 봉이 잘려 나가 주간 종가를 못 찾는다.
                val base = apiBase(code, unit, candles)
                model.addAttribute("candleChart", CandleChart(candles.takeLast(MAX_MINUTE_CANDLES), levels, base?.close, base?.dayChange))
            }
            .onFailure {
                log.warn { "[선물옵션] 캔들 조회 실패: ${it.message}" }
                model.addAttribute("chartError", it.message)
            }
    }

    /** 등락률 기준 종가. [dayChange] 는 야간일 때만 있는, 그 주간 종가의 전일 대비 등락률(%)로 보여주기용이다. */
    private data class ApiBase(val close: BigDecimal, val dayChange: BigDecimal? = null)

    /**
     * 등락률 기준 종가를 한투 API 로 구한다(직접 계산하지 않는다). 정규장이면 전일 종가(`futs_prdy_clpr`),
     * 야간장이면 마지막 정규장 종가(`futs_prpr`)와 그 전일 대비 등락률(`futs_prdy_ctrt`) — 둘 다 `inquire-price` 한 번이다
     * (REST 는 야간 체결을 담지 않는다). 못 구하면 null → 차트가 봉에서 찾는다.
     */
    private fun apiBase(
        code: String,
        unit: String,
        candles: List<Candle>,
    ): ApiBase? {
        if (unit == "D") return null
        return runCatching {
            if (isNightBar(candles.last())) {
                client.lastRegularQuote(code)?.let { ApiBase(it.close, it.changePercent) }
            } else {
                client.previousClose(code)?.let { ApiBase(it) }
            }
        }.getOrNull()
    }

    /** 일봉은 한투 일봉, 1분봉은 앱이 실시간 체결(주간·야간 구독)로 쌓은 분봉이다. 1분봉은 한투를 부르지 않아 폴링해도 호출이 늘지 않는다. */
    private fun candlesOf(
        code: String,
        unit: String,
    ): List<Candle> = if (unit == "D") client.candles(code, false, LocalDate.now(clock)) else night.recent(code)

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

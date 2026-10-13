package com.quantlog.futures

import mu.KotlinLogging
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
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
                }
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
        unit: String,
    ) {
        val code = futures.firstOrNull()?.get("futs_shrn_iscd")?.takeIf { it.isNotBlank() } ?: return
        runCatching { candlesOf(code, unit) }
            .onSuccess { candles ->
                if (candles.isEmpty()) return@onSuccess
                val levels = (model.getAttribute("board") as? OptionBoard)?.keyLevels().orEmpty()
                model.addAttribute("candleChart", CandleChart(candles, levels, apiBase(code, unit, candles)))
            }
            .onFailure {
                log.warn { "[선물옵션] 캔들 조회 실패: ${it.message}" }
                model.addAttribute("chartError", it.message)
            }
    }

    /**
     * 등락률 기준 종가를 한투 API 로 구한다(직접 계산하지 않는다). 정규장이면 전일 종가(`inquire-price`),
     * 야간장이면 그날 주간 종가(한투 일봉에서 마지막 정규장 봉의 날짜 행). 못 구하면 null → 차트가 봉에서 찾는다.
     */
    private fun apiBase(
        code: String,
        unit: String,
        candles: List<Candle>,
    ): BigDecimal? {
        if (unit == "D") return null
        return runCatching {
            if (isNightBar(candles.last())) {
                val dayDate = candles.lastOrNull { !isNightBar(it) }?.date ?: return@runCatching null
                client.candles(code, false, LocalDate.now(clock)).lastOrNull { it.date == dayDate }?.close
            } else {
                client.previousClose(code)
            }
        }.getOrNull()
    }

    /** 일봉은 한투 일봉, 1분봉은 한투 정규장 분봉에 앱이 쌓은 야간장 분봉을 이어 붙인다(한투 분봉은 야간을 주지 않는다). */
    private fun candlesOf(
        code: String,
        unit: String,
    ): List<Candle> {
        val today = LocalDate.now(clock)
        if (unit == "D") return client.candles(code, false, today)
        val regular = runCatching { client.candles(code, true, today) }
        val nightCandles = night.recent(code)
        // 정규장 조회가 실패해도 야간 봉이 있으면 보여준다. 둘 다 없으면 정규장 오류를 그대로 알린다.
        regular.exceptionOrNull()?.takeIf { nightCandles.isEmpty() }?.let { throw it }
        return (regular.getOrDefault(emptyList()) + nightCandles).sortedWith(
            compareBy({ it.date }, { it.time }),
        ).takeLast(MAX_MINUTE_CANDLES)
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

package com.quantlog.futures

import mu.KotlinLogging
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeParseException

private val log = KotlinLogging.logger {}

/** 선물옵션 화면: 지수옵션 매물대(행사가별 미결제약정)와 지수선물 전광판. 다음 날 매매 종목을 고를 때 시장 분위기를 보는 용도. */
@Controller
class FuturesController(
    private val client: FuturesOptionsClient,
    private val clock: Clock,
) {
    @GetMapping("/futures")
    fun page(
        @RequestParam(required = false) maturity: String?,
        model: Model,
    ): String {
        val month = maturity?.let { parse(it) } ?: OptionMaturity.nearest(LocalDate.now(clock))
        model.addAttribute("maturity", month.toString())
        model.addAttribute("expiry", OptionMaturity.expiry(month))
        // 두 조회는 서로 독립이라 한쪽이 실패해도 다른 쪽은 보여준다.
        runCatching { client.optionBoard(month) }
            .onSuccess { model.addAttribute("board", it) }
            .onFailure {
                log.warn { "[선물옵션] 옵션 전광판 조회 실패: ${it.message}" }
                model.addAttribute("optionError", it.message)
            }
        runCatching { client.futuresBoard() }
            .onSuccess { model.addAttribute("futures", it) }
            .onFailure {
                log.warn { "[선물옵션] 선물 전광판 조회 실패: ${it.message}" }
                model.addAttribute("futuresError", it.message)
            }
        return "futures"
    }

    private fun parse(text: String): YearMonth? =
        try {
            YearMonth.parse(text)
        } catch (e: DateTimeParseException) {
            null
        }
}

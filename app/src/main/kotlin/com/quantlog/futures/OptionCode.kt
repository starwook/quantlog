package com.quantlog.futures

import java.math.BigDecimal
import java.time.YearMonth

/**
 * 코스피200 옵션 단축코드(2026-10-13 실측, docs/kis-api/futures-options.md).
 * `B01`(콜)·`C01`(풋) + 연도 끝자리 + 월 2자리 + 행사가 3자리. 행사가 3자리는 1000 미만이면 행사가의 정수부(997.5 → `997`),
 * 1000 이상이면 낮은 행사가부터 매긴 순번(`A01` = 1000.0, `A02`, … `A99`, `B01`, …)이라 행사가를 코드로 바로 못 구한다.
 */
object OptionCode {
    /** 1000 미만 행사가의 코드. */
    fun below1000(
        call: Boolean,
        maturity: YearMonth,
        strike: BigDecimal,
    ): String {
        require(strike < THOUSAND) { "1000 이상 행사가는 순번 코드다: $strike" }
        return prefix(call, maturity) + "%03d".format(strike.toInt())
    }

    /** 1000 이상 행사가의 [order] 번째(1부터) 코드. */
    fun from1000(
        call: Boolean,
        maturity: YearMonth,
        order: Int,
    ): String {
        require(order >= 1) { "순번은 1부터다: $order" }
        val letter = 'A' + (order - 1) / PER_LETTER
        return prefix(call, maturity) + letter + "%02d".format((order - 1) % PER_LETTER + 1)
    }

    private fun prefix(
        call: Boolean,
        maturity: YearMonth,
    ) = (if (call) "B01" else "C01") + maturity.year % 10 + "%02d".format(maturity.monthValue)

    val THOUSAND: BigDecimal = BigDecimal(1000)
    private const val PER_LETTER = 99
}

package com.quantlog.futures

import com.fasterxml.jackson.databind.JsonNode
import java.math.BigDecimal
import java.math.RoundingMode

/** 옵션 한 종목(콜 또는 풋)의 전광판 값. [openInterest] 는 미결제약정 수량. */
data class OptionQuote(
    val code: String,
    val price: BigDecimal,
    val openInterest: Long,
    val openInterestChange: Long,
    val volume: Long,
)

/** 행사가 하나의 콜·풋. 한쪽만 있을 수 있다. */
data class OptionStrike(
    val strike: BigDecimal,
    val call: OptionQuote?,
    val put: OptionQuote?,
    val atm: Boolean,
) {
    val callOi: Long get() = call?.openInterest ?: 0
    val putOi: Long get() = put?.openInterest ?: 0
}

/**
 * 한 만기월의 행사가별 콜·풋 전광판(한투 `display-board-callput`). 옵션 매물대(미결제약정이 몰린 행사가)를 여기서 계산한다.
 * 한투는 매물대 API 가 없어, 행사가별 미결제약정(`hts_otst_stpl_qty`)으로 앱이 구한다.
 */
data class OptionBoard(val strikes: List<OptionStrike>) {
    /** 콜 미결제약정이 가장 큰 행사가 — 위쪽 저항으로 본다. */
    val callWall: OptionStrike? = strikes.maxByOrNull { it.callOi }?.takeIf { it.callOi > 0 }

    /** 풋 미결제약정이 가장 큰 행사가 — 아래쪽 지지로 본다. */
    val putWall: OptionStrike? = strikes.maxByOrNull { it.putOi }?.takeIf { it.putOi > 0 }

    /** 풋/콜 미결제약정 비율. 콜이 0이면 null. */
    val putCallRatio: BigDecimal? =
        strikes.sumOf { it.callOi }.takeIf { it > 0 }?.let { call ->
            BigDecimal(strikes.sumOf { it.putOi }).divide(BigDecimal(call), 2, RoundingMode.HALF_UP)
        }

    /** 맥스페인: 만기 때 옵션 매수자 전체의 손실(= 매도자 이익)이 가장 커지는 행사가. */
    val maxPain: BigDecimal? =
        strikes.takeIf { it.any { s -> s.callOi + s.putOi > 0 } }?.minByOrNull { settle ->
            strikes.sumOf { k ->
                BigDecimal(k.callOi) * (settle.strike - k.strike).max(BigDecimal.ZERO) +
                    BigDecimal(k.putOi) * (k.strike - settle.strike).max(BigDecimal.ZERO)
            }
        }?.strike

    /** 막대 길이를 맞추는 기준(콜·풋 통틀어 가장 큰 미결제약정). */
    val maxOi: Long = strikes.maxOfOrNull { maxOf(it.callOi, it.putOi) } ?: 0

    companion object {
        /**
         * 한투 응답 → [OptionBoard]. `output1` 이 콜, `output2` 가 풋이라고 읽는다(HTS 종합시세 화면 순서 — 실측으로 확인 필요,
         * docs/kis-api/futures-options.md). 행사가(`acpr`)로 맞붙이고 행사가 오름차순으로 정렬한다.
         */
        fun parse(body: JsonNode): OptionBoard {
            val calls = quotes(body.path("output1"))
            val puts = quotes(body.path("output2"))
            val strikes =
                (calls.keys + puts.keys).sorted().map { strike ->
                    val atm = listOf(calls[strike], puts[strike]).any { it?.second == true }
                    OptionStrike(strike, calls[strike]?.first, puts[strike]?.first, atm)
                }
            return OptionBoard(strikes)
        }

        private fun quotes(rows: JsonNode): Map<BigDecimal, Pair<OptionQuote, Boolean>> =
            rows.mapNotNull { row ->
                val strike = row.path("acpr").asText().trim().toBigDecimalOrNull() ?: return@mapNotNull null
                val quote =
                    OptionQuote(
                        code = row.path("optn_shrn_iscd").asText(),
                        price = row.path("optn_prpr").asText().trim().toBigDecimalOrNull() ?: BigDecimal.ZERO,
                        openInterest = row.long("hts_otst_stpl_qty"),
                        openInterestChange = row.long("otst_stpl_qty_icdc"),
                        volume = row.long("acml_vol"),
                    )
                strike to (quote to row.path("atm_cls_name").asText().contains("ATM", ignoreCase = true))
            }.toMap()

        private fun JsonNode.long(field: String): Long = path(field).asText().trim().toBigDecimalOrNull()?.toLong() ?: 0
    }
}

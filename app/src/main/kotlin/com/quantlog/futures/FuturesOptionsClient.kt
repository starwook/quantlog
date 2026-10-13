package com.quantlog.futures

import com.fasterxml.jackson.databind.JsonNode
import com.quantlog.broker.KisApiException
import com.quantlog.broker.KisRest
import org.springframework.stereotype.Component
import java.math.BigDecimal

/** 옵션 한 종목의 현재가 조회 결과. [underlying] 은 같은 응답에 실려 오는 KOSPI200 지수. */
data class OptionPrice(
    val strike: BigDecimal,
    val quote: OptionQuote,
    val underlying: BigDecimal?,
)

/**
 * 한투 국내선물옵션 시세 조회(주문 없음). 사양·실측: docs/kis-api/futures-options.md.
 * 호출 우선순위는 기본(최후순위)이다 — 수십 번 이어 부르는 수집이 주문 흐름을 막지 않게 한다.
 */
@Component
class FuturesOptionsClient(private val rest: KisRest) {
    /** 옵션 한 종목 현재가(`inquire-price`). 그 코드의 종목이 없으면 null(한투는 오류 없이 빈 값을 준다). */
    fun optionPrice(code: String): OptionPrice? {
        val body =
            fetch(
                "/uapi/domestic-futureoption/v1/quotations/inquire-price",
                "FHMIF10000000",
                mapOf("FID_COND_MRKT_DIV_CODE" to "O", "FID_INPUT_ISCD" to code),
            )
        val row = body.path("output1")
        if (row.path("hts_kor_isnm").asText().isBlank()) return null
        val strike = row.decimal("acpr") ?: return null
        val quote =
            OptionQuote(
                price = row.decimal("futs_prpr") ?: BigDecimal.ZERO,
                openInterest = row.long("hts_otst_stpl_qty"),
                openInterestChange = row.long("otst_stpl_qty_icdc"),
                volume = row.long("acml_vol"),
            )
        // output3 이 KOSPI200(업종코드 2001), output2 는 코스피 종합이다.
        return OptionPrice(strike, quote, body.path("output3").decimal("bstp_nmix_prpr")?.takeIf { it.signum() > 0 })
    }

    /** 코스피200 지수선물 전광판(월물별). 값을 해석하지 않고 행(필드명 → 값)으로 돌려준다. */
    fun futuresBoard(): List<Map<String, String>> {
        val body =
            fetch(
                "/uapi/domestic-futureoption/v1/quotations/display-board-futures",
                "FHPIF05030200",
                mapOf("FID_COND_MRKT_DIV_CODE" to "F", "FID_COND_SCR_DIV_CODE" to "20503", "FID_COND_MRKT_CLS_CODE" to "K2I"),
            )
        return body.path("output").map { row -> row.fields().asSequence().associate { (k, v) -> k to v.asText() } }
    }

    private fun fetch(
        path: String,
        trId: String,
        params: Map<String, String>,
    ): JsonNode {
        val body = rest.get(path, trId, params).body
        if (body.path("rt_cd").asText() != "0") {
            val code = body.path("msg_cd").asText()
            throw KisApiException("KIS 오류 $path ($trId): [$code] ${body.path("msg1").asText()}", code)
        }
        return body
    }

    private fun JsonNode.decimal(field: String): BigDecimal? = path(field).asText().trim().toBigDecimalOrNull()

    private fun JsonNode.long(field: String): Long = decimal(field)?.toLong() ?: 0
}

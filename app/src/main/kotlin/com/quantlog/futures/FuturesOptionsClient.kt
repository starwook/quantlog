package com.quantlog.futures

import com.fasterxml.jackson.databind.JsonNode
import com.quantlog.broker.KisApiException
import com.quantlog.broker.KisRest
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter

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

    /**
     * 지수선물 봉(`output2`). 분봉은 최신부터 오지만 과거 → 최근 순서로 돌려준다.
     * 분봉은 모의투자 미지원이라 모의 서버에서는 실패할 수 있다.
     */
    fun candles(
        code: String,
        minute: Boolean,
        today: LocalDate,
    ): List<Candle> {
        val body =
            if (minute) {
                fetch(
                    "/uapi/domestic-futureoption/v1/quotations/inquire-time-fuopchartprice",
                    "FHKIF03020200",
                    mapOf(
                        "FID_COND_MRKT_DIV_CODE" to "F",
                        "FID_INPUT_ISCD" to code,
                        "FID_HOUR_CLS_CODE" to "60",
                        "FID_PW_DATA_INCU_YN" to "N",
                        "FID_FAKE_TICK_INCU_YN" to "N",
                        // 이 날짜·시각 이전 봉부터 최신순으로 온다. 빈 값은 한투가 거절한다(INVALID FID_INPUT_DATE_1).
                        "FID_INPUT_DATE_1" to today.format(DateTimeFormatter.BASIC_ISO_DATE),
                        "FID_INPUT_HOUR_1" to "235959",
                    ),
                )
            } else {
                fetch(
                    "/uapi/domestic-futureoption/v1/quotations/inquire-daily-fuopchartprice",
                    "FHKIF03020100",
                    mapOf(
                        "FID_COND_MRKT_DIV_CODE" to "F",
                        "FID_INPUT_ISCD" to code,
                        "FID_INPUT_DATE_1" to today.minusDays(DAILY_DAYS).format(DateTimeFormatter.BASIC_ISO_DATE),
                        "FID_INPUT_DATE_2" to today.format(DateTimeFormatter.BASIC_ISO_DATE),
                        "FID_PERIOD_DIV_CODE" to "D",
                    ),
                )
            }
        return body.path("output2").mapNotNull { row ->
            Candle(
                date = row.path("stck_bsop_date").asText(),
                time = if (minute) row.path("stck_cntg_hour").asText() else null,
                open = row.decimal("futs_oprc") ?: return@mapNotNull null,
                high = row.decimal("futs_hgpr") ?: return@mapNotNull null,
                low = row.decimal("futs_lwpr") ?: return@mapNotNull null,
                close = row.decimal("futs_prpr") ?: return@mapNotNull null,
                volume = row.long(if (minute) "cntg_vol" else "acml_vol"),
            )
        }.sortedWith(compareBy({ it.date }, { it.time ?: "" }))
    }

    /** 선물 한 종목의 전일 종가(`inquire-price` 의 `futs_prdy_clpr`). 값이 없으면 null. */
    fun previousClose(code: String): BigDecimal? =
        fetch(
            "/uapi/domestic-futureoption/v1/quotations/inquire-price",
            "FHMIF10000000",
            mapOf("FID_COND_MRKT_DIV_CODE" to "F", "FID_INPUT_ISCD" to code),
        ).path("output1").decimal("futs_prdy_clpr")?.takeIf { it.signum() > 0 }

    /** 가장 가까운 월물 선물의 종목코드(전광판 첫 행의 `futs_shrn_iscd`). 없으면 null. */
    fun nearestFuturesCode(): String? = futuresBoard().firstOrNull()?.get("futs_shrn_iscd")?.takeIf { it.isNotBlank() }

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

    private companion object {
        /** 일봉 조회 기간(달력 일수). 한투는 최대 100건을 준다. */
        const val DAILY_DAYS = 150L
    }
}

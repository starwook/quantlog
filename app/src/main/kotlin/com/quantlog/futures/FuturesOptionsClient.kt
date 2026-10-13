package com.quantlog.futures

import com.fasterxml.jackson.databind.JsonNode
import com.quantlog.broker.CallPriority
import com.quantlog.broker.KisApiException
import com.quantlog.broker.KisRest
import org.springframework.stereotype.Component
import java.time.YearMonth

/** 한투 국내선물옵션 시세 조회(주문 없음). 응답을 해석해 [OptionBoard]·행 목록으로 돌려준다. 사양: docs/kis-api/futures-options.md. */
@Component
class FuturesOptionsClient(private val rest: KisRest) {
    /** 지수옵션 전광판(콜·풋 행사가별). 사용자가 화면을 연 즉발 요청이다. */
    fun optionBoard(maturity: YearMonth): OptionBoard =
        OptionBoard.parse(
            fetch(
                "/uapi/domestic-futureoption/v1/quotations/display-board-callput",
                "FHPIF05030100",
                mapOf(
                    "FID_COND_MRKT_DIV_CODE" to "O",
                    "FID_COND_SCR_DIV_CODE" to "20503",
                    "FID_MRKT_CLS_CODE" to "CO",
                    "FID_MTRT_CNT" to "%04d%02d".format(maturity.year, maturity.monthValue),
                    "FID_MRKT_CLS_CODE1" to "PO",
                    "FID_COND_MRKT_CLS_CODE" to "",
                ),
            ),
        )

    /** 지수선물 전광판. 응답 필드명이 실측 전이라 값을 해석하지 않고 행(필드명 → 값)으로 돌려준다. */
    fun futuresBoard(): List<Map<String, String>> {
        val body =
            fetch(
                "/uapi/domestic-futureoption/v1/quotations/display-board-futures",
                "FHPIF05030200",
                mapOf("FID_COND_MRKT_DIV_CODE" to "F", "FID_COND_SCR_DIV_CODE" to "20503", "FID_COND_MRKT_CLS_CODE" to "K2I"),
            )
        return listOf("output", "output1", "output2").map { body.path(it) }.flatMap { rows ->
            when {
                rows.isArray -> rows.toList()
                rows.isObject -> listOf(rows)
                else -> emptyList()
            }
        }.map { row -> row.fields().asSequence().associate { (k, v) -> k to v.asText() } }
    }

    private fun fetch(
        path: String,
        trId: String,
        params: Map<String, String>,
    ): JsonNode {
        val body = CallPriority.urgent { rest.get(path, trId, params) }.body
        if (body.path("rt_cd").asText() != "0") {
            val code = body.path("msg_cd").asText()
            throw KisApiException("KIS 오류 $path ($trId): [$code] ${body.path("msg1").asText()}", code)
        }
        return body
    }
}

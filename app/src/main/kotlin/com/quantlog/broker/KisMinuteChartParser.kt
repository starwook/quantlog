package com.quantlog.broker

import com.fasterxml.jackson.databind.JsonNode
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * 한투 주식당일분봉조회(FHKST03010200) 응답에서 분봉을 읽는다. `output2` 에 최신 분봉이 맨 앞인 내림차순으로, 한 번에 최대 30건이 온다.
 * 필드명은 2026-09-29 실측(삼성전자)으로 확인했다 — docs/kis-api/README.md.
 */
object KisMinuteChartParser {
    private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val HOUR_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HHmmss")

    fun parse(response: JsonNode): List<MinuteCandle> =
        response.path("output2").map { node ->
            MinuteCandle(
                date = LocalDate.parse(node.path("stck_bsop_date").asText(), DATE_FORMAT),
                time = LocalTime.parse(node.path("stck_cntg_hour").asText(), HOUR_FORMAT),
                open = node.decimal("stck_oprc"),
                high = node.decimal("stck_hgpr"),
                low = node.decimal("stck_lwpr"),
                close = node.decimal("stck_prpr"),
                volume = node.path("cntg_vol").asText().toLong(),
            )
        }
}

/** 한투 숫자 필드는 문자열이고 비어 있을 수 있다 — 비면 0. */
internal fun JsonNode.decimal(field: String): BigDecimal {
    val text = path(field).asText("").trim()
    return if (text.isEmpty()) BigDecimal.ZERO else BigDecimal(text)
}

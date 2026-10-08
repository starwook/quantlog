package com.quantlog.broker

import com.fasterxml.jackson.databind.JsonNode
import java.math.BigDecimal

/**
 * 한투 주식잔고조회(VTTC8434R) 응답에서 보유 종목을 읽는다. 종목별 행은 `output1` — 종목코드 `pdno`, 종목명 `prdt_name`,
 * 보유수량 `hldg_qty`, 매입평균가 `pchs_avg_pric`, 현재가 `prpr`. 수량이 0 인 행은 뺀다.
 * 게이트웨이는 이 응답을 원문 그대로 `kis_balance` 에 쌓고, 칸 이름이 바뀌면 여기만 고친다.
 */
object KisBalanceParser {
    /** 잔고조회 요청 파라미터(계좌번호 제외 — 게이트웨이가 끼운다). */
    val REQUEST_PARAMS: Map<String, String> =
        mapOf(
            "AFHR_FLPR_YN" to "N",
            "OFL_YN" to "",
            "INQR_DVSN" to "02",
            "UNPR_DVSN" to "01",
            "FUND_STTL_ICLD_YN" to "N",
            "FNCG_AMT_AUTO_RDPT_YN" to "N",
            "PRCS_DVSN" to "00",
            "CTX_AREA_FK100" to "",
            "CTX_AREA_NK100" to "",
        )

    fun parse(response: JsonNode): List<Holding> =
        response.path("output1").mapNotNull { row ->
            Holding(
                market = Market.KR,
                symbol = row.path("pdno").asText(),
                name = row.path("prdt_name").asText(),
                quantity = row.decimal("hldg_qty"),
                averagePrice = row.decimal("pchs_avg_pric"),
                currentPrice = row.decimal("prpr"),
            ).takeIf { it.quantity > BigDecimal.ZERO }
        }
}

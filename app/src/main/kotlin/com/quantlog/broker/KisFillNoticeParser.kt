package com.quantlog.broker

/**
 * 게이트웨이가 원문 그대로 저장한 한투 실시간 체결통보(`kis_broker_fill.body`, `^` 구분 한 건)를 [FillNotice] 로 읽는다.
 * 필드 위치는 한투 공식 문서와 모의 실측을 따른다(docs/kis-api/field-reference.md 3절) — 바뀌면 여기만 고치면 되고 게이트웨이는 다시 띄우지 않는다.
 * 고객 ID·계좌번호·계좌명 칸은 게이트웨이가 비워서 저장한다.
 */
object KisFillNoticeParser {
    /** 국내 체결통보 TR(모의 H0STCNI9, 실전 H0STCNI0). */
    val DOMESTIC_TR_IDS = setOf("H0STCNI9", "H0STCNI0")

    /** 해석할 수 없는 TR 이거나 필드가 모자라면 null. */
    fun parse(
        trId: String,
        body: String,
    ): FillNotice? {
        if (trId !in DOMESTIC_TR_IDS) return null
        val f = body.split("^")
        if (f.size < MIN_FIELD_COUNT) return null
        return parseDomestic(f)
    }

    /**
     * 국내 모의 실측(2026-10-07, 한 건 23필드): 2 주문번호 · 3 원주문번호 · 4 매도/매수(01/02) · 8 종목 · 9 수량 · 10 단가 · 11 시각 · 12 거부 ·
     * 13 체결여부(1 접수/2 체결) · 14 접수여부 · 16 주문수량 · 22 주문가(체결 통보에서만). 접수 통보의 9·10 은 주문 수량·주문가다.
     */
    private fun parseDomestic(f: List<String>): FillNotice {
        val fill = f[13] == FillNotice.FILLED_FLAG
        return FillNotice(
            symbol = f[8],
            orderNo = f[2],
            originalOrderNo = f[3],
            sellBuyCode = f[4],
            filledFlag = f[13],
            acceptFlag = f[14],
            refuseFlag = f[12],
            filledQuantity = f[9].toBigDecimalOrNull().takeIf { fill },
            filledPrice = f[10].toBigDecimalOrNull().takeIf { fill },
            orderQuantity = f[16].toBigDecimalOrNull(),
            orderPrice = (if (fill) f.getOrNull(22) else f[10])?.toBigDecimalOrNull(),
            time = f[11],
        )
    }

    private const val MIN_FIELD_COUNT = 17
}

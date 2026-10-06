package com.quantlog.broker

import java.math.BigDecimal

/**
 * 증권사가 실시간으로 밀어준 체결통보 한 건 ([PriceTick] 처럼 이벤트로 발행된다).
 * 코드값(구분·체결여부 등)은 실측 전이라 해석하지 않고 원문 그대로 둔다 — docs/kis-api/field-reference.md "체결통보" 참고.
 * 계좌번호·고객 ID 같은 식별 필드는 일부러 담지 않는다.
 */
data class FillNotice(
    /** 해외 체결통보면 true. 해외 통보엔 거래소 필드가 없어서 [Market] 은 알 수 없다. */
    val overseas: Boolean,
    val symbol: String,
    val orderNo: String,
    val originalOrderNo: String,
    /** SELN_BYOV_CLS: 매도/매수 구분 코드 원문. */
    val sellBuyCode: String,
    /** CNTG_YN: "2" 면 체결통보, "1" 이면 접수·정정·취소·거부 통보. */
    val filledFlag: String,
    /** ACPT_YN: 접수 여부 원문. */
    val acceptFlag: String,
    /** RFUS_YN: 거부 여부 원문. */
    val refuseFlag: String,
    /** CNTG_QTY. 부분체결일 때 건별인지 누적인지는 아직 모른다. */
    val filledQuantity: BigDecimal?,
    val filledPrice: BigDecimal?,
    val orderQuantity: BigDecimal?,
    /** STCK_CNTG_HOUR 원문(HHmmss). */
    val time: String,
) {
    val isFill: Boolean get() = filledFlag == FILLED_FLAG

    private companion object {
        const val FILLED_FLAG = "2"
    }
}

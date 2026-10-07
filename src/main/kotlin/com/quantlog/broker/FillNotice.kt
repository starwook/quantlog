package com.quantlog.broker

import java.math.BigDecimal

/**
 * 증권사가 실시간으로 밀어준 체결통보 한 건 ([PriceTick] 처럼 이벤트로 발행된다).
 * 한 주문은 보통 접수 통보(CNTG_YN=1)와 체결 통보(CNTG_YN=2) 두 건으로 온다 — 같은 필드라도 값의 의미가 다르다:
 * 접수 통보의 수량·단가 칸은 **주문값**이라 [filledQuantity]·[filledPrice] 를 채우지 않고, 체결 통보에서만 채운다.
 * 국내 모의 실측(2026-10-07) 기준이고, 해외·부분체결은 아직 실측 전이다 — docs/kis-api/field-reference.md 3절.
 * 계좌번호·고객 ID·계좌명 같은 식별 필드는 일부러 담지 않는다.
 */
data class FillNotice(
    /** 해외 체결통보면 true. 해외 통보엔 거래소 필드가 없어서 [Market] 은 알 수 없다. */
    val overseas: Boolean,
    val symbol: String,
    val orderNo: String,
    val originalOrderNo: String,
    /** SELN_BYOV_CLS 원문. 국내는 "01" 매도 / "02" 매수 ([side]). */
    val sellBuyCode: String,
    /** CNTG_YN: "2" 면 체결통보, "1" 이면 접수·정정·취소·거부 통보. */
    val filledFlag: String,
    /** ACPT_YN 원문. 국내 모의에서 접수 통보는 "1", 체결 통보는 "2". */
    val acceptFlag: String,
    /** RFUS_YN: 거부 여부 원문. 정상이면 "0". */
    val refuseFlag: String,
    /** 이 체결 통보의 체결 수량. 접수 통보면 null. 부분체결일 때 건별인지 누적인지는 아직 모른다. */
    val filledQuantity: BigDecimal?,
    /** 이 체결 통보의 체결 단가(지정가보다 유리할 수 있다). 접수 통보면 null. */
    val filledPrice: BigDecimal?,
    val orderQuantity: BigDecimal?,
    /** 주문 가격(지정가). 국내만 채운다. 해외는 위치를 아직 모른다. */
    val orderPrice: BigDecimal?,
    /** STCK_CNTG_HOUR 원문(HHmmss). */
    val time: String,
) {
    val isFill: Boolean get() = filledFlag == FILLED_FLAG

    /** 매수/매도. 해외 코드값은 실측 전이라 모르면 null. */
    val side: Side?
        get() =
            if (overseas) {
                null
            } else {
                when (sellBuyCode) {
                    "01" -> Side.SELL
                    "02" -> Side.BUY
                    else -> null
                }
            }

    /** 로그용 한 줄 요약. 계좌 식별 정보는 애초에 담지 않는다. */
    fun summary(): String =
        "${if (overseas) "해외" else "국내"} ${if (isFill) "체결" else "접수"} 종목=$symbol 주문번호=$orderNo " +
            "구분=${side ?: sellBuyCode} 거부=$refuseFlag 주문수량=$orderQuantity 주문가=$orderPrice " +
            "체결수량=$filledQuantity 체결단가=$filledPrice 시각=$time"

    companion object {
        const val FILLED_FLAG = "2"
    }
}

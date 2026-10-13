package com.quantlog.futures

import java.math.BigDecimal
import java.math.RoundingMode

/** 옵션 한 종목(콜 또는 풋)의 값. [openInterest] 는 미결제약정 수량. */
data class OptionQuote(
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
) {
    val callOi: Long get() = call?.openInterest ?: 0
    val putOi: Long get() = put?.openInterest ?: 0
}

/**
 * 한 만기월의 행사가별 콜·풋. 옵션 매물대(미결제약정이 몰린 행사가)를 여기서 계산한다.
 * 한투는 매물대 API 가 없어, 행사가별 미결제약정으로 앱이 구한다. [underlying] 은 기초지수(KOSPI200).
 */
class OptionBoard(
    strikes: List<OptionStrike>,
    val underlying: BigDecimal?,
) {
    val strikes: List<OptionStrike> = strikes.sortedBy { it.strike }

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

    /** 기초지수에 가장 가까운 행사가(등가격). */
    val atmStrike: BigDecimal? = underlying?.let { index -> strikes.minByOrNull { (it.strike - index).abs() }?.strike }

    /** 막대 길이를 맞추는 기준(콜·풋 통틀어 가장 큰 미결제약정). */
    val maxOi: Long = strikes.maxOfOrNull { maxOf(it.callOi, it.putOi) } ?: 0
}

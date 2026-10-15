package com.quantlog.futures

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime

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

/** 가격 사다리에 표시할 핵심 가격. [gapPercent] 는 기준 가격 대비 거리(%), [reference] 는 그 기준 가격인 줄이다(거리를 안 붙인다). */
data class KeyLevel(
    val label: String,
    val price: BigDecimal,
    val gapPercent: BigDecimal?,
    val reference: Boolean = false,
)

/**
 * 한 만기월의 행사가별 콜·풋. 옵션 매물대(미결제약정이 몰린 행사가)를 여기서 계산한다.
 * 한투는 매물대 API 가 없어, 행사가별 미결제약정으로 앱이 구한다. [underlying] 은 저장 시점의 KOSPI200 지수로,
 * 수집 범위를 정할 때만 쓰고 화면에는 보이지 않는다(전날 종가라 오늘 선물과 비교할 수 없다).
 */
class OptionBoard(
    strikes: List<OptionStrike>,
    val underlying: BigDecimal?,
    /** 저장본을 모은 시각. 모르면 null. */
    val collectedAt: LocalDateTime? = null,
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

    /** [price](선물 현재가)에 가장 가까운 행사가(등가격). */
    fun atmStrike(price: BigDecimal?): BigDecimal? = price?.let { p -> strikes.minByOrNull { (it.strike - p).abs() }?.strike }

    /** 막대 길이를 맞추는 기준(콜·풋 통틀어 가장 큰 미결제약정). */
    val maxOi: Long = strikes.maxOfOrNull { maxOf(it.callOi, it.putOi) } ?: 0

    /** 가격 사다리용: 높은 행사가가 위로 오게 뒤집은 목록. */
    val strikesTopDown: List<OptionStrike> = this.strikes.reversed()

    /**
     * 저항·지지·맥스페인·선물 현재가를 높은 가격부터 나열한다. 매수·매도 타점을 가늠하는 기준선.
     * 거리(%)의 기준은 [futuresPrice](선물 현재가)이고, 없으면 거리를 붙이지 않는다.
     */
    fun keyLevels(futuresPrice: BigDecimal? = null): List<KeyLevel> =
        listOfNotNull(
            callWall?.let { "콜 최대 (저항)" to it.strike },
            maxPain?.let { "맥스페인" to it },
            putWall?.let { "풋 최대 (지지)" to it.strike },
            futuresPrice?.let { "선물 현재가" to it },
        ).sortedByDescending { it.second }.map { (label, price) ->
            val reference = label == "선물 현재가"
            val gap =
                futuresPrice?.takeIf { it.signum() != 0 && !reference }
                    ?.let { (price - it).multiply(BigDecimal(100)).divide(it, 2, RoundingMode.HALF_UP) }
            KeyLevel(label, price, gap, reference)
        }
}

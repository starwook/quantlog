package com.quantlog.broker

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalTime

/** 시세 한 건. tickSize 는 이 가격대에서 주문 가능한 가격 간격(호가 단위)이다. */
data class Quote(
    val price: BigDecimal,
    val tickSize: BigDecimal,
) {
    init {
        require(tickSize > BigDecimal.ZERO) { "tickSize must be positive: $tickSize" }
    }

    /** value 를 호가 단위의 배수로 맞춘다. 기본은 가장 가까운 호가. */
    fun roundToTick(
        value: BigDecimal,
        mode: RoundingMode = RoundingMode.HALF_UP,
    ): BigDecimal = value.divide(tickSize, 0, mode).multiply(tickSize)

    /**
     * 즉시 체결용 지정가: 현재가보다 한 호가 불리한 가격. 현재가 자체가 호가 단위에 안 맞을 수 있어(예: 276,250 / 단위 500)
     * 호가 단위로 올림·내림한다 — 안 맞으면 증권사가 호가단위 오류로 거부한다.
     */
    fun oneTickAbove(): BigDecimal = roundToTick(price.add(tickSize), RoundingMode.CEILING)

    fun oneTickBelow(): BigDecimal = roundToTick(price.subtract(tickSize), RoundingMode.FLOOR)
}

/** 분봉 한 건. 국내만 확인됨(KIS FHKST03010200). open/high/low/close 는 그 1분 동안, volume 은 그 1분간 체결량. */
data class MinuteCandle(
    val date: LocalDate,
    val time: LocalTime,
    val open: BigDecimal,
    val high: BigDecimal,
    val low: BigDecimal,
    val close: BigDecimal,
    val volume: Long,
)

data class OrderRequest(
    val market: Market,
    val symbol: String,
    val side: Side,
    val quantity: Int,
    val limitPrice: BigDecimal,
) {
    init {
        require(symbol.isNotBlank()) { "symbol is required" }
        require(quantity > 0) { "quantity must be positive: $quantity" }
        require(limitPrice > BigDecimal.ZERO) { "limitPrice must be positive: $limitPrice" }
    }

    val notional: BigDecimal get() = limitPrice.multiply(BigDecimal(quantity))
}

data class OrderReceipt(
    val orderNo: String,
    val message: String,
    /** 국내 정정·취소에 필요한 주문조직번호(KRX_FWDG_ORD_ORGNO). */
    val branchNo: String = "",
)

/**
 * 접수된 주문의 상태(증권사 체결조회 결과). 조회가 비었다고 미체결로 단정하지 않으려고 셋으로 나눈다.
 * - [Filled]: 주문 수량이 전부 체결됨. 평균 체결가.
 * - [Open]: 증권사가 주문을 확인했고 아직 (전부) 체결되지 않았다 — 진짜 미체결.
 * - [Unknown]: 체결조회에 아직 안 잡힌다(반영 전이거나 조회 실패) — 미체결인지 체결인지 모른다.
 */
sealed interface OrderStatus {
    data class Filled(val price: BigDecimal) : OrderStatus

    data object Open : OrderStatus

    data object Unknown : OrderStatus
}

/** 접수된 주문 1건의 취소 요청. 수량·가격은 원주문 그대로다. */
data class CancelRequest(
    val market: Market,
    val symbol: String,
    val orderNo: String,
    val branchNo: String,
    val quantity: Int,
    val limitPrice: BigDecimal,
)

data class Holding(
    val market: Market,
    val symbol: String,
    val name: String,
    val quantity: BigDecimal,
    val averagePrice: BigDecimal,
    val currentPrice: BigDecimal,
    /** 한투 잔고가 알려 주는 이 종목의 당일 누적 매수·매도 수량(`thdt_buyqty`·`thdt_sll_qty`). 응답에 없으면 null. */
    val boughtToday: BigDecimal? = null,
    val soldToday: BigDecimal? = null,
)

data class BuyingPower(
    val currency: String,
    val orderableAmount: BigDecimal,
    val maxQuantity: BigDecimal,
)

/**
 * 증권사가 알려 준 주문 한 건의 체결 누적(쪼개진 체결 한 건씩이 아니다 — 체결 시각도 없다). [averagePrice] 는 체결평균가(`avg_prvs`),
 * [filledAmount] 는 총 체결금액(`tot_ccld_amt`), [remainingQuantity] 는 아직 호가창에 남은 수량(`rmn_qty`). 모르면 null.
 */
data class OrderFillTotal(
    val orderNo: String,
    val filledQuantity: Int,
    val averagePrice: BigDecimal,
    val filledAmount: BigDecimal? = null,
    val remainingQuantity: Int? = null,
) {
    /**
     * 호가창에 남은 수량이 없어 끝난 주문인가(전량 체결 또는 취소). **0 뿐 아니라 음수도 끝난 것이다** — 취소와 마지막 체결이 겹치면 한투가 둘 다 세어
     * 남은 수량을 음수로 준다(2026-10-28 실측 0000035103: 주문 60, 체결 40, 취소확인 30 → `rmn_qty` -10). 0 만 보다가 이 주문이 끝내 끝남 처리되지 않아
     * 청산 대기가 풀리지 않고 233740 손절이 며칠간 막혔다.
     */
    val isClosed: Boolean get() = remainingQuantity != null && remainingQuantity <= 0
}

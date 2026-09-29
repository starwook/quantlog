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
)

data class Holding(
    val market: Market,
    val symbol: String,
    val name: String,
    val quantity: BigDecimal,
    val averagePrice: BigDecimal,
    val currentPrice: BigDecimal,
)

data class BuyingPower(
    val currency: String,
    val orderableAmount: BigDecimal,
    val maxQuantity: BigDecimal,
)

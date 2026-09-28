package com.quantlog.broker

import java.math.BigDecimal

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

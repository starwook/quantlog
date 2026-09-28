package com.quantlog.broker

import java.math.BigDecimal

interface BrokerClient {
    fun currentPrice(
        market: Market,
        symbol: String,
    ): BigDecimal

    fun buyingPower(
        market: Market,
        symbol: String,
        price: BigDecimal,
    ): BuyingPower

    /** 해외는 미국 전체(NASDAQ/NYSE/AMEX)를 한 번에 조회한다. 수량 0인 종목은 제외한다. */
    fun holdings(market: Market): List<Holding>

    fun placeOrder(order: OrderRequest): OrderReceipt
}

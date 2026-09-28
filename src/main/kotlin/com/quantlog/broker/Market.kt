package com.quantlog.broker

enum class Market(val currency: String) {
    KR("KRW"),
    NASDAQ("USD"),
    NYSE("USD"),
    AMEX("USD"),
    ;

    val isOverseas: Boolean get() = this != KR
}

enum class Side { BUY, SELL }

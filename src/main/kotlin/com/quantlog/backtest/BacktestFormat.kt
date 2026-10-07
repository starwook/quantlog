package com.quantlog.backtest

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/** 백테스트 화면들이 같이 쓰는 숫자 표시 규칙. 색은 한국식(상승 빨강 pos, 하락 파랑 neg). */
internal fun BigDecimal.money(currency: String): String {
    val scale = if (currency == "KRW") 0 else 2
    return String.format(Locale.US, "%,.${scale}f", this)
}

internal fun signedPercent(value: BigDecimal): String {
    val v = value.setScale(2, RoundingMode.HALF_UP)
    val sign = if (v > BigDecimal.ZERO) "+" else ""
    return "$sign${v.toPlainString()}%"
}

internal fun signedMoney(
    value: BigDecimal,
    currency: String,
): String = (if (value.signum() > 0) "+" else "") + value.money(currency)

internal fun pnlCss(value: BigDecimal): String =
    when {
        value > BigDecimal.ZERO -> "pos"
        value < BigDecimal.ZERO -> "neg"
        else -> "zero"
    }

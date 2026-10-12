package com.quantlog.common

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

// 화면이 같이 쓰는 숫자 표시 규칙. 색은 한국식(상승 빨강 pos, 하락 파랑 neg).

/** 원화는 소수점 없이 천 단위 구분, 그 외는 소수점 둘째 자리까지. */
fun BigDecimal.money(currency: String): String = String.format(Locale.US, "%,.${if (currency == "KRW") 0 else 2}f", this)

/** 부호가 의미 있는 금액(손익)에 쓴다. 음수는 [money] 가 이미 "-"를 붙이니 양수에만 "+"를 더한다. */
fun signedMoney(
    value: BigDecimal,
    currency: String,
): String = (if (value.signum() > 0) "+" else "") + value.money(currency)

/** 소수점 둘째 자리 HALF_UP 퍼센트. 양수에만 "+". */
fun signedPercent(value: BigDecimal): String {
    val v = value.setScale(2, RoundingMode.HALF_UP)
    return "${if (v.signum() > 0) "+" else ""}${v.toPlainString()}%"
}

/** 부호 붙인 금액과 퍼센트. 예: +3,000 (+1.10%) */
fun signedMoneyAndPercent(
    amount: BigDecimal,
    percent: BigDecimal,
    currency: String,
): String = "${signedMoney(amount, currency)} (${signedPercent(percent)})"

/** 한국 증권가 관행: 이익=빨강(pos), 손실=파랑(neg), 0=zero. */
fun pnlCss(amount: BigDecimal): String =
    when {
        amount.signum() > 0 -> "pos"
        amount.signum() < 0 -> "neg"
        else -> "zero"
    }

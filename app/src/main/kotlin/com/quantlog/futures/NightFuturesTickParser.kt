package com.quantlog.futures

import java.math.BigDecimal
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** 야간선물 실시간 체결(H0MFCNT0) 한 건. */
data class NightFuturesTick(
    val code: String,
    val time: LocalTime,
    val price: BigDecimal,
    val quantity: Long,
)

/**
 * 게이트웨이가 그대로 넘긴 한투 실시간 메시지(`0|H0MFCNT0|001|A01612^180001^...`)를 해석한다.
 * 칸 순서는 docs/kis-api/futures-options.md 의 `H0MFCNT0` 출력 50칸 — 어긋나면 여기만 고친다. 게이트웨이는 다시 띄우지 않는다.
 */
object NightFuturesTickParser {
    const val TR_ID = "H0MFCNT0"
    private const val FIELDS_PER_RECORD = 50
    private const val CODE_INDEX = 0
    private const val TIME_INDEX = 1
    private const val PRICE_INDEX = 5
    private const val QUANTITY_INDEX = 9
    private val HOUR_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HHmmss")

    /** 야간선물 체결이 아니거나 못 읽는 건은 건너뛴다. */
    fun parse(raw: String): List<NightFuturesTick> {
        val parts = raw.split("|", limit = 4)
        if (parts.size < 4 || parts[1] != TR_ID) return emptyList()
        val fields = parts[3].split("^")
        val count = parts[2].toIntOrNull()?.coerceAtLeast(1) ?: 1
        val width = if (count > 1 && fields.size % count == 0) fields.size / count else FIELDS_PER_RECORD
        return (0 until count).mapNotNull { i ->
            val record = fields.drop(i * width).take(width)
            if (record.size <= QUANTITY_INDEX) return@mapNotNull null
            val time = runCatching { LocalTime.parse(record[TIME_INDEX], HOUR_FORMAT) }.getOrNull() ?: return@mapNotNull null
            val price = record[PRICE_INDEX].toBigDecimalOrNull() ?: return@mapNotNull null
            NightFuturesTick(record[CODE_INDEX], time, price, record[QUANTITY_INDEX].toLongOrNull() ?: 0L)
        }
    }
}

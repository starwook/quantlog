package com.quantlog.gatewayclient

import java.math.BigDecimal
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** 한투 실시간 체결가(H0STCNT0) 한 건에서 앱이 쓰는 값. */
data class RealtimeTrade(
    val symbol: String,
    val time: LocalTime,
    val price: BigDecimal,
    val volume: Long,
)

/**
 * 게이트웨이가 그대로 넘긴 한투 실시간 메시지(`0|H0STCNT0|001|005930^093001^70100^...`)를 해석한다.
 * 칸 순서는 한투 문서(examples_llm/domestic_stock/ccnl_krx)를 따른다 — 어긋나면 여기만 고친다. 게이트웨이는 다시 띄우지 않는다.
 * 한 메시지에 여러 건이 붙어 올 수 있어(세 번째 칸이 건수) 칸 수로 나눈다.
 */
object KisRealtimeTickParser {
    const val TR_ID = "H0STCNT0"
    private const val FIELDS_PER_RECORD = 46
    private const val SYMBOL_INDEX = 0
    private const val TIME_INDEX = 1
    private const val PRICE_INDEX = 2
    private const val CNTG_VOL_INDEX = 12
    private val HOUR_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HHmmss")

    /** 시세 메시지가 아니거나 못 읽는 건은 건너뛴다. */
    fun parse(raw: String): List<RealtimeTrade> {
        val parts = raw.split("|", limit = 4)
        if (parts.size < 4 || parts[1] != TR_ID) return emptyList()
        val fields = parts[3].split("^")
        val count = parts[2].toIntOrNull()?.coerceAtLeast(1) ?: 1
        val width = if (count > 1 && fields.size % count == 0) fields.size / count else FIELDS_PER_RECORD
        return (0 until count).mapNotNull { i ->
            val record = fields.drop(i * width).take(width)
            if (record.size <= CNTG_VOL_INDEX) return@mapNotNull null
            val time = runCatching { LocalTime.parse(record[TIME_INDEX], HOUR_FORMAT) }.getOrNull() ?: return@mapNotNull null
            val price = record[PRICE_INDEX].toBigDecimalOrNull() ?: return@mapNotNull null
            RealtimeTrade(record[SYMBOL_INDEX], time, price, record[CNTG_VOL_INDEX].toLongOrNull() ?: 0L)
        }
    }
}

package com.quantlog.futures

import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** 봉 하나. [time] 은 분봉일 때만 있다(HHmmss). */
data class Candle(
    val date: String,
    val time: String?,
    val open: BigDecimal,
    val high: BigDecimal,
    val low: BigDecimal,
    val close: BigDecimal,
    val volume: Long,
)

/** 브라우저 차트(lightweight-charts)에 그대로 넘기는 봉. [time] 은 일봉이면 `yyyy-MM-dd`, 분봉이면 한국 시각을 UTC 로 본 초 단위 값이다. */
data class ChartPoint(
    val time: Any,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long,
)

/** 야간장(18:00~06:00, 06:00 마감 봉 포함) 분봉인지. */
fun isNightBar(c: Candle) = c.time != null && (c.time >= "180000" || c.time <= "060000")

/** 차트에 겹쳐 그릴 가격선(저항·지지·맥스페인 등). */
data class ChartLine(
    val label: String,
    val price: Double,
)

/**
 * 차트 한 장에 필요한 값. [candles] 는 과거 → 최근 순서이고 비어 있으면 안 된다. 그리기와 확대·축소는 브라우저가 한다.
 * [apiBase] 는 한투 API 가 준 기준 종가(정규장이면 전일 종가, 야간이면 그날 주간 종가)로, 있으면 이것을 쓰고
 * 없을 때만 봉 목록에서 직접 찾는다.
 */
class CandleChart(
    val candles: List<Candle>,
    levels: List<KeyLevel>,
    apiBase: BigDecimal? = null,
) {
    private val daily = candles.first().time == null

    /**
     * [apiBase] 가 없을 때 봉에서 찾는 기준 종가. 일봉은 직전 봉 종가, 1분봉은 야간장이면 그날 주간 종가(15:45 마지막 정규장 봉),
     * 정규장이면 전일 정규장 마지막 봉 종가.
     */
    private val baseCandle: Candle? =
        if (daily) {
            candles.getOrNull(candles.size - 2)
        } else if (isNight(candles.last())) {
            candles.lastOrNull { !isNight(it) }
        } else {
            candles.lastOrNull { !isNight(it) && it.date < candles.last().date }
        }

    val baseline: Double? = (if (daily) baseCandle?.close else apiBase ?: baseCandle?.close)?.toDouble()

    val baselineLabel: String = if (!daily && isNight(candles.last())) "주간 종가" else "전일 종가"

    val points: List<ChartPoint> =
        candles.map { ChartPoint(timeOf(it), it.open.toDouble(), it.high.toDouble(), it.low.toDouble(), it.close.toDouble(), it.volume) }

    val lines: List<ChartLine> = levels.map { ChartLine(it.label, it.price.toDouble()) }

    /** 첫·마지막 봉의 날짜(분봉은 시각까지) 표기. */
    val from: String = label(candles.first())
    val to: String = label(candles.last())

    private fun timeOf(c: Candle): Any =
        if (daily) {
            LocalDate.parse(c.date, DATE).toString()
        } else {
            LocalDateTime.of(LocalDate.parse(c.date, DATE), java.time.LocalTime.parse(c.time, TIME)).toEpochSecond(ZoneOffset.UTC)
        }

    private fun isNight(c: Candle) = isNightBar(c)

    private fun label(c: Candle) = c.date + (c.time?.let { " " + it.take(4).chunked(2).joinToString(":") } ?: "")

    private companion object {
        val DATE: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HHmmss")
    }
}

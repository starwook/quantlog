package com.quantlog.futures

import java.math.BigDecimal
import kotlin.math.abs

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

/** 화면에 그릴 캔들 하나의 좌표(SVG 단위). */
data class CandleBar(
    val x: Double,
    val bodyWidth: Double,
    val wickTop: Double,
    val wickBottom: Double,
    val bodyTop: Double,
    val bodyHeight: Double,
    val up: Boolean,
)

/** 차트 위에 겹쳐 그릴 가격선(저항·지지·맥스페인 등). */
data class ChartLine(
    val label: String,
    val price: BigDecimal,
    val y: Double,
)

/**
 * 캔들 차트의 좌표 계산. [candles] 는 과거 → 최근 순서이고 비어 있으면 안 된다.
 * [levels] 중 캔들 가격대에서 너무 먼 것은 그리지 않는다. 가로 [WIDTH] x 세로 [HEIGHT] 의 SVG 좌표계를 쓴다.
 */
class CandleChart(
    val candles: List<Candle>,
    levels: List<KeyLevel>,
) {
    private val candleLow = candles.minOf { it.low }.toDouble()
    private val candleHigh = candles.maxOf { it.high }.toDouble()
    private val reach = (candleHigh - candleLow).coerceAtLeast(1.0)
    private val shown = levels.filter { it.price.toDouble() in (candleLow - reach)..(candleHigh + reach) }
    private val min = minOf(candleLow, shown.minOfOrNull { it.price.toDouble() } ?: candleLow)
    private val max = maxOf(candleHigh, shown.maxOfOrNull { it.price.toDouble() } ?: candleHigh)
    private val span = (max - min).coerceAtLeast(0.01)

    private fun y(price: Double) = PAD + (max - price) / span * (HEIGHT - 2 * PAD)

    val bars: List<CandleBar> =
        candles.mapIndexed { i, c ->
            val step = WIDTH / candles.size
            val open = y(c.open.toDouble())
            val close = y(c.close.toDouble())
            CandleBar(
                x = step * (i + 0.5),
                bodyWidth = step * 0.6,
                wickTop = y(c.high.toDouble()),
                wickBottom = y(c.low.toDouble()),
                bodyTop = minOf(open, close),
                bodyHeight = maxOf(abs(open - close), 1.0),
                up = c.close >= c.open,
            )
        }

    val lines: List<ChartLine> = shown.map { ChartLine(it.label, it.price, y(it.price.toDouble())) }

    /** 첫·마지막 봉의 날짜(분봉은 시각까지) 표기. */
    val from: String = label(candles.first())
    val to: String = label(candles.last())

    private fun label(c: Candle) = c.date + (c.time?.let { " " + it.take(4).chunked(2).joinToString(":") } ?: "")

    companion object {
        const val WIDTH = 600.0
        const val HEIGHT = 300.0
        private const val PAD = 10.0
    }
}

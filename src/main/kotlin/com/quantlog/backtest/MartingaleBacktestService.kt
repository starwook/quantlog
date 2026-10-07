package com.quantlog.backtest

import com.quantlog.broker.MinuteCandle
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalTime

/** 마틴게일 백테스트 입력. 비율은 % 단위(0.5 = 0.5%), 수량 증가는 MartingaleRule 과 같다(보유 수량 × (multiplier - 1) 만큼 더 산다). */
data class MartingaleParams(
    val dropPercent: BigDecimal,
    val multiplier: Int,
    val maxStages: Int,
    val takeProfitPercent: BigDecimal,
    val stopLossPercent: BigDecimal?,
    val startQuantity: Long,
    /** true 면 청산 다음 분봉 시가에 다시 시작 수량을 산다(주기 재매수 옵션과 비슷한 효과). false 면 하루 한 사이클만. */
    val reenter: Boolean,
)

data class MartingaleCycleResult(
    val startTime: LocalTime,
    val endTime: LocalTime,
    val stages: Int,
    val peakQuantity: Long,
    val averagePrice: BigDecimal,
    val exitPrice: BigDecimal,
    val pnl: BigDecimal,
    val maxInvested: BigDecimal,
    val exitReason: String,
)

data class MartingaleDayResult(
    val params: MartingaleParams,
    val candleCount: Int,
    val cycles: List<MartingaleCycleResult>,
) {
    val pnl: BigDecimal get() = cycles.fold(BigDecimal.ZERO) { acc, c -> acc.add(c.pnl) }

    /** 사이클은 차례로 이어지므로 필요한 돈은 사이클별 최대 투입금 중 가장 큰 값이다. */
    val maxInvested: BigDecimal get() = cycles.maxOfOrNull { it.maxInvested } ?: BigDecimal.ZERO
    val maxStage: Int get() = cycles.maxOfOrNull { it.stages } ?: 0
    val returnPercent: BigDecimal? get() = percentOf(pnl, maxInvested)
}

/** 한 날짜의 사용자 설정 결과 + 그날을 사후적으로 가장 잘 먹었을 조합. */
data class MartingaleDayRow(
    val date: LocalDate,
    val rangePercent: BigDecimal?,
    val result: MartingaleDayResult,
    val best: MartingaleDayResult?,
)

/** 한 조합(간격·익절·최대 단계)을 여러 날에 돌린 합계. */
data class MartingaleSweepRow(
    val dropPercent: BigDecimal,
    val takeProfitPercent: BigDecimal,
    val maxStages: Int,
    val totalPnl: BigDecimal,
    val worstDayPnl: BigDecimal,
    val maxInvested: BigDecimal,
    val days: Int,
    val winDays: Int,
) {
    val returnPercent: BigDecimal? get() = percentOf(totalPnl, maxInvested)
}

private fun percentOf(
    value: BigDecimal,
    base: BigDecimal,
): BigDecimal? = if (base.signum() == 0) null else value.multiply(BigDecimal(100)).divide(base, 3, RoundingMode.HALF_UP)

/**
 * 쌓인 분봉으로 "마틴게일을 이 값으로 돌렸으면 어땠을까"를 재생한다. 청산 규칙은 라이브와 같다(익절 % 도달 시 전량, 손절은 평단 기준).
 *
 * 분봉 안의 가격 순서는 알 수 없어서 불리한 쪽으로 가정한다: 한 분봉 안에서는 저가 쪽 사건(추가매수 → 손절)을 먼저 처리하고,
 * 그 뒤에 고가로 익절을 확인한다. 추가매수·청산은 트리거 가격에 체결된다고 보되 시가가 이미 그 너머로 갭이 났으면 시가로 체결한다.
 * 호가 단위·수수료·세금·슬리피지는 반영하지 않은 근사치다.
 */
@Service
class MartingaleBacktestService {
    fun simulate(
        candlesAscending: List<MinuteCandle>,
        params: MartingaleParams,
    ): MartingaleDayResult {
        val cycles = mutableListOf<MartingaleCycleResult>()
        var quantity = 0L
        var cost = BigDecimal.ZERO
        var stages = 0
        var startTime = LocalTime.MIN
        var maxInvested = BigDecimal.ZERO
        var peakQuantity = 0L

        fun average(): BigDecimal = cost.divide(BigDecimal(quantity), MathContext.DECIMAL64)

        fun close(
            time: LocalTime,
            price: BigDecimal,
            reason: String,
        ) {
            val average = average()
            cycles +=
                MartingaleCycleResult(
                    startTime = startTime,
                    endTime = time,
                    stages = stages,
                    peakQuantity = peakQuantity,
                    averagePrice = average,
                    exitPrice = price,
                    pnl = price.subtract(average).multiply(BigDecimal(quantity)),
                    maxInvested = maxInvested,
                    exitReason = reason,
                )
            quantity = 0
            cost = BigDecimal.ZERO
        }

        candlesAscending.forEach { candle ->
            if (quantity == 0L) {
                if (cycles.isNotEmpty() && !params.reenter) return@forEach
                quantity = params.startQuantity
                cost = candle.open.multiply(BigDecimal(quantity))
                stages = 1
                startTime = candle.time
                maxInvested = cost
                peakQuantity = quantity
            }

            while (stages < params.maxStages) {
                val trigger = average().multiply(percentFactor(params.dropPercent.negate()))
                if (candle.low > trigger) break
                if (stopPrice(average(), params)?.let { candle.low <= it && it >= trigger } == true) break
                val fill = trigger.min(candle.open)
                val addQuantity = quantity * (params.multiplier - 1)
                cost = cost.add(fill.multiply(BigDecimal(addQuantity)))
                quantity += addQuantity
                stages++
                maxInvested = maxInvested.max(cost)
                peakQuantity = quantity
            }

            val stop = stopPrice(average(), params)
            if (stop != null && candle.low <= stop) {
                close(candle.time, stop.min(candle.open), "손절")
                return@forEach
            }
            val target = average().multiply(percentFactor(params.takeProfitPercent))
            if (candle.high >= target) {
                close(candle.time, target.max(candle.open), "익절")
            }
        }

        val last = candlesAscending.lastOrNull()
        if (quantity > 0 && last != null) close(last.time, last.close, "미청산")
        return MartingaleDayResult(params, candlesAscending.size, cycles)
    }

    /** 그날 고저폭(%): (최고가 - 최저가) / 첫 시가. */
    fun rangePercent(candles: List<MinuteCandle>): BigDecimal? {
        if (candles.isEmpty()) return null
        val open = candles.first().open
        return candles.maxOf { it.high }.subtract(candles.minOf { it.low }).multiply(BigDecimal(100)).divide(open, 2, RoundingMode.HALF_UP)
    }

    /** 날짜별 결과: 사용자 설정 + 그날의 사후 최적 조합([grid] 중 손익이 가장 큰 것, 같으면 필요 자금이 작은 것). */
    fun dayRows(
        candlesByDate: Map<LocalDate, List<MinuteCandle>>,
        params: MartingaleParams,
        grid: SweepGrid,
    ): List<MartingaleDayRow> =
        candlesByDate.map { (date, candles) ->
            MartingaleDayRow(
                date = date,
                rangePercent = rangePercent(candles),
                result = simulate(candles, params),
                best = grid.combos(params).map { simulate(candles, it) }.minWithOrNull(BEST_FIRST),
            )
        }

    /** 조합마다 모든 날짜에 돌려 합계가 큰 순으로 돌려준다. */
    fun sweep(
        candlesByDate: Map<LocalDate, List<MinuteCandle>>,
        params: MartingaleParams,
        grid: SweepGrid,
    ): List<MartingaleSweepRow> =
        grid
            .combos(params)
            .map { combo ->
                val days = candlesByDate.values.map { simulate(it, combo) }
                MartingaleSweepRow(
                    dropPercent = combo.dropPercent,
                    takeProfitPercent = combo.takeProfitPercent,
                    maxStages = combo.maxStages,
                    totalPnl = days.fold(BigDecimal.ZERO) { acc, d -> acc.add(d.pnl) },
                    worstDayPnl = days.minOfOrNull { it.pnl } ?: BigDecimal.ZERO,
                    maxInvested = days.maxOfOrNull { it.maxInvested } ?: BigDecimal.ZERO,
                    days = days.size,
                    winDays = days.count { it.pnl.signum() > 0 },
                )
            }.sortedWith(compareByDescending<MartingaleSweepRow> { it.totalPnl }.thenBy { it.maxInvested })

    private fun stopPrice(
        average: BigDecimal,
        params: MartingaleParams,
    ): BigDecimal? = params.stopLossPercent?.let { average.multiply(percentFactor(it.negate())) }

    private fun percentFactor(percent: BigDecimal): BigDecimal = BigDecimal.ONE.add(percent.movePointLeft(2))

    private companion object {
        val BEST_FIRST: Comparator<MartingaleDayResult> = compareByDescending<MartingaleDayResult> { it.pnl }.thenBy { it.maxInvested }
    }
}

/** 훑어볼 간격·익절·최대 단계 후보. 손절이 간격보다 작은 조합은 설정 화면에서 저장이 안 되므로 뺀다. */
data class SweepGrid(
    val dropPercents: List<BigDecimal> = listOf("0.3", "0.5", "0.7", "1.0").map(::BigDecimal),
    val takeProfitPercents: List<BigDecimal> = listOf("0.3", "0.5", "0.7", "1.0").map(::BigDecimal),
    val maxStages: List<Int> = listOf(3, 4, 5, 6, 7),
) {
    fun combos(base: MartingaleParams): List<MartingaleParams> =
        dropPercents.flatMap { drop ->
            takeProfitPercents.flatMap { tp ->
                maxStages.mapNotNull { stages ->
                    if (base.stopLossPercent != null && base.stopLossPercent < drop) {
                        null
                    } else {
                        base.copy(dropPercent = drop, takeProfitPercent = tp, maxStages = stages)
                    }
                }
            }
        }
}

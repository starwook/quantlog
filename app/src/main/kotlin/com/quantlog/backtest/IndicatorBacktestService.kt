package com.quantlog.backtest

import com.quantlog.broker.MinuteCandle
import com.quantlog.indicator.IndicatorAction
import com.quantlog.indicator.IndicatorEvaluator
import com.quantlog.indicator.IndicatorPreset
import com.quantlog.indicator.PresetParams
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalTime

data class IndicatorBacktestParams(
    val preset: IndicatorPreset,
    val presetParams: PresetParams,
    val takeProfitPercent: BigDecimal,
    /** null 이면 손절 없음(라이브의 "손절 보류"와 같다). */
    val stopLossPercent: BigDecimal?,
    /** 지표 매도 옵션. 끄면 매도 신호는 근거로만 보이고 팔지 않는다. */
    val sellOnSignal: Boolean,
)

data class IndicatorTrade(
    val entryTime: LocalTime,
    val entryPrice: BigDecimal,
    val exitTime: LocalTime,
    val exitPrice: BigDecimal,
    val returnPercent: BigDecimal,
    val entryReason: String,
    val exitReason: String,
)

data class IndicatorDayResult(
    val date: LocalDate,
    val candleCount: Int,
    val trades: List<IndicatorTrade>,
) {
    val returnPercent: BigDecimal get() = trades.fold(BigDecimal.ZERO) { acc, t -> acc.add(t.returnPercent) }
    val winCount: Int get() = trades.count { it.returnPercent > BigDecimal.ZERO }
}

/** 여러 날 합계. 수익률은 거래별 수익률(%)의 단순 합이고, 최대 낙폭은 그 누적합이 고점에서 가장 깊이 빠진 폭(%p)이다. */
data class IndicatorSummary(
    val days: Int,
    val tradeCount: Int,
    val winCount: Int,
    val totalReturnPercent: BigDecimal,
    val maxDrawdownPercent: BigDecimal,
    val winDays: Int,
) {
    val winRatePercent: BigDecimal?
        get() =
            if (tradeCount == 0) {
                null
            } else {
                BigDecimal(winCount).multiply(BigDecimal(100)).divide(BigDecimal(tradeCount), 1, RoundingMode.HALF_UP)
            }
}

/**
 * 지표 전략 백테스트. 쌓인 분봉으로 "이 프리셋대로 했으면 그날 어땠을까"를 재생한다. 판정은 라이브가 쓸 [IndicatorEvaluator] 그대로다.
 * - 매수: 보유가 없을 때, 매수 조건이 거짓→참이 된 봉의 종가에 산다(원칙집 "지표 전략 옵션").
 * - 매도: 다음 봉부터 본다. 한 봉 안의 가격 순서는 모르니 불리하게 — 손절(저가) → 익절(고가) → 지표 매도(종가) 순이다.
 *   손절·익절은 그 가격에 체결된다고 보되, 시가가 이미 그 너머로 갭이 났으면 시가로 체결한다.
 * - 판정용으로 직전 거래일 분봉을 앞에 붙이지만, 매매는 그날 봉에서만 한다. 그날 끝까지 못 판 건 마지막 종가로 "미청산".
 * 호가 단위·수수료·세금·슬리피지는 반영하지 않은 근사치다(다른 백테스트와 같다).
 */
@Service
class IndicatorBacktestService {
    fun simulateDay(
        date: LocalDate,
        previousDay: List<MinuteCandle>,
        day: List<MinuteCandle>,
        params: IndicatorBacktestParams,
    ): IndicatorDayResult {
        if (day.isEmpty()) return IndicatorDayResult(date, 0, emptyList())
        val candles = previousDay + day
        val timeline = IndicatorEvaluator.timeline(params.preset, params.presetParams, candles)
        val trades = mutableListOf<IndicatorTrade>()
        var position: OpenPosition? = null

        for (i in previousDay.size until candles.size) {
            val candle = candles[i]
            val open = position
            if (open == null) {
                if (timeline.actionAt(i) == IndicatorAction.BUY) {
                    position = OpenPosition(candle.time, candle.close, "매수 신호: ${timeline.states[i]!!.reason}")
                }
                continue
            }
            val exit = exitOf(open, candle, timeline.actionAt(i), timeline.states[i]?.reason, params) ?: continue
            trades += open.close(candle.time, exit.first, exit.second)
            position = null
        }

        position?.let { trades += it.close(day.last().time, day.last().close, "미청산(그날 마지막 종가)") }
        return IndicatorDayResult(date, day.size, trades)
    }

    fun summarize(days: List<IndicatorDayResult>): IndicatorSummary {
        var equity = BigDecimal.ZERO
        var peak = BigDecimal.ZERO
        var maxDrawdown = BigDecimal.ZERO
        days.sortedBy { it.date }.flatMap { it.trades }.forEach { trade ->
            equity = equity.add(trade.returnPercent)
            peak = peak.max(equity)
            maxDrawdown = maxDrawdown.max(peak.subtract(equity))
        }
        return IndicatorSummary(
            days = days.size,
            tradeCount = days.sumOf { it.trades.size },
            winCount = days.sumOf { it.winCount },
            totalReturnPercent = equity,
            maxDrawdownPercent = maxDrawdown,
            winDays = days.count { it.returnPercent > BigDecimal.ZERO },
        )
    }

    /** 이 봉에서 팔면 (체결가, 사유). 안 팔면 null. */
    private fun exitOf(
        open: OpenPosition,
        candle: MinuteCandle,
        action: IndicatorAction,
        reason: String?,
        params: IndicatorBacktestParams,
    ): Pair<BigDecimal, String>? {
        params.stopLossPercent?.let { stop ->
            val stopPrice = open.price.multiply(BigDecimal.ONE.subtract(stop.movePointLeft(2)))
            if (candle.low <= stopPrice) return candle.open.min(stopPrice) to "손절"
        }
        val takeProfitPrice = open.price.multiply(BigDecimal.ONE.add(params.takeProfitPercent.movePointLeft(2)))
        if (candle.high >= takeProfitPrice) return candle.open.max(takeProfitPrice) to "익절"
        if (params.sellOnSignal && action == IndicatorAction.SELL) return candle.close to "매도 신호: $reason"
        return null
    }

    private class OpenPosition(val time: LocalTime, val price: BigDecimal, val reason: String) {
        fun close(
            exitTime: LocalTime,
            exitPrice: BigDecimal,
            exitReason: String,
        ) = IndicatorTrade(time, price, exitTime, exitPrice, percentChange(price, exitPrice), reason, exitReason)
    }
}

private fun percentChange(
    from: BigDecimal,
    to: BigDecimal,
): BigDecimal = to.subtract(from).divide(from, 6, RoundingMode.HALF_UP).multiply(BigDecimal(100))

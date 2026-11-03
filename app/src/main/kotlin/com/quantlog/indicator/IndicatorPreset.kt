package com.quantlog.indicator

import java.math.BigDecimal

/** 프리셋 파라미터 하나. 화면 입력칸의 범위도 이 값을 쓴다. */
data class ParamSpec(
    val key: String,
    val label: String,
    val default: BigDecimal,
    val min: BigDecimal,
    val max: BigDecimal,
    val step: BigDecimal,
) {
    constructor(key: String, label: String, default: Number, min: Number, max: Number, step: Number) :
        this(
            key,
            label,
            BigDecimal(default.toString()),
            BigDecimal(min.toString()),
            BigDecimal(max.toString()),
            BigDecimal(step.toString()),
        )
}

/** 프리셋에 넣을 값. 빠졌거나 범위를 벗어난 값은 기본값·범위 끝으로 맞춘다. */
class PresetParams private constructor(private val values: Map<String, BigDecimal>) {
    fun int(key: String): Int = values.getValue(key).toInt()

    fun double(key: String): Double = values.getValue(key).toDouble()

    fun decimal(key: String): BigDecimal = values.getValue(key)

    companion object {
        fun of(
            preset: IndicatorPreset,
            raw: Map<String, BigDecimal?> = emptyMap(),
        ) = PresetParams(
            preset.params.associate { spec ->
                spec.key to (raw[spec.key] ?: spec.default).coerceIn(spec.min, spec.max)
            },
        )
    }
}

/**
 * 한 봉 시점에서 매수·매도 조건이 참인지와 그 근거. 주문은 조건이 거짓→참으로 바뀐 순간에만 낸다([IndicatorEvaluator]).
 * 조건을 판단할 만큼 봉이 안 쌓였으면 상태 자체가 없다(null).
 */
data class BarState(val buy: Boolean, val sell: Boolean, val reason: String)

/**
 * 지표 전략 프리셋 13개(docs/지표-전략-설계.md "프리셋"). 한투 전략빌더 10개는 원래 일봉("N일")이라 분봉("N봉")으로 옮겼고,
 * 분봉에서 뜻이 바뀌는 둘(모멘텀·52주 신고가)은 이름을 바꿨다. 한투 코드는 옮기지 않고 조건만 다시 썼다(라이선스 없음).
 * 매도 조건이 없는 프리셋은 sell 이 늘 false 다.
 */
enum class IndicatorPreset(
    val label: String,
    val description: String,
    val origin: String,
    val params: List<ParamSpec>,
) {
    GOLDEN_CROSS(
        "골든크로스",
        "단기 이동평균이 장기 이동평균을 위로 뚫으면 매수, 아래로 뚫으면 매도",
        "한투",
        listOf(ParamSpec("fast", "단기 MA(봉)", 5, 2, 60, 1), ParamSpec("slow", "장기 MA(봉)", 20, 3, 240, 1)),
    ) {
        override fun requiredBars(p: PresetParams) = p.int("slow")

        override fun state(
            c: IndicatorContext,
            i: Int,
            p: PresetParams,
        ): BarState {
            val fast = c.sma(p.int("fast"), i)
            val slow = c.sma(p.int("slow"), i)
            return BarState(fast > slow, fast < slow, "MA${p.int("fast")} ${fmt(fast)} / MA${p.int("slow")} ${fmt(slow)}")
        }
    },
    TREND_FILTER(
        "추세 필터",
        "이동평균 위에서 직전 봉보다 오르면 매수, 아래에서 내리면 매도",
        "한투",
        listOf(ParamSpec("period", "MA(봉)", 60, 10, 240, 5)),
    ) {
        override fun requiredBars(p: PresetParams) = p.int("period")

        override fun state(
            c: IndicatorContext,
            i: Int,
            p: PresetParams,
        ): BarState {
            val ma = c.sma(p.int("period"), i)
            val change = c.changePercent(i)
            return BarState(
                c.close(i) > ma && change > 0,
                c.close(i) < ma && change < 0,
                "종가 ${fmt(c.close(i))} / MA${p.int("period")} ${fmt(ma)} · 직전 대비 ${pct(change)}",
            )
        }
    },
    DISPARITY(
        "이격도",
        "종가 ÷ 이동평균(%)이 매수 기준 아래면 매수, 매도 기준 위면 매도",
        "한투",
        listOf(
            ParamSpec("period", "MA(봉)", 20, 5, 120, 1),
            ParamSpec("buy", "매수 기준(%)", 99.0, 90, 99.9, 0.1),
            ParamSpec("sell", "매도 기준(%)", 101.0, 100.1, 110, 0.1),
        ),
    ) {
        override fun requiredBars(p: PresetParams) = p.int("period")

        override fun state(
            c: IndicatorContext,
            i: Int,
            p: PresetParams,
        ): BarState {
            val disparity = c.close(i) / c.sma(p.int("period"), i) * 100
            return BarState(disparity < p.double("buy"), disparity > p.double("sell"), "이격도 ${"%.2f".format(disparity)}%")
        }
    },
    MEAN_REVERSION(
        "평균회귀",
        "종가가 N봉 평균보다 매수 이탈만큼 낮으면 매수, 매도 이탈만큼 높으면 매도",
        "한투",
        listOf(
            ParamSpec("period", "평균(봉)", 5, 3, 60, 1),
            ParamSpec("buy", "매수 이탈(%)", -0.5, -5, -0.1, 0.1),
            ParamSpec("sell", "매도 이탈(%)", 0.5, 0.1, 5, 0.1),
        ),
    ) {
        override fun requiredBars(p: PresetParams) = p.int("period")

        override fun state(
            c: IndicatorContext,
            i: Int,
            p: PresetParams,
        ): BarState {
            val deviation = (c.close(i) / c.sma(p.int("period"), i) - 1) * 100
            return BarState(deviation <= p.double("buy"), deviation >= p.double("sell"), "${p.int("period")}봉 평균 대비 ${pct(deviation)}")
        }
    },
    CONSECUTIVE(
        "연속 상승",
        "N봉 연속 종가가 오르면 매수, N봉 연속 내리면 매도",
        "한투 \"연속 상승·하락\"",
        listOf(ParamSpec("bars", "연속 봉 수", 5, 2, 15, 1)),
    ) {
        override fun requiredBars(p: PresetParams) = p.int("bars") + 1

        override fun state(
            c: IndicatorContext,
            i: Int,
            p: PresetParams,
        ): BarState {
            val up = c.consecutive(i, up = true)
            val down = c.consecutive(i, up = false)
            return BarState(up >= p.int("bars"), down >= p.int("bars"), "연속 상승 ${up}봉 · 연속 하락 ${down}봉")
        }
    },
    VOLATILITY_EXPANSION(
        "변동성 확장",
        "변동성이 최근 최저권(최저 × 1.1 이하)일 때 직전 봉 대비 돌파 기준 이상 오르면 매수, 그만큼 내리면 매도",
        "한투",
        listOf(ParamSpec("period", "변동성 기간(봉)", 10, 5, 60, 1), ParamSpec("breakout", "돌파 기준(%)", 0.3, 0.05, 3, 0.05)),
    ) {
        override fun requiredBars(p: PresetParams) = p.int("period") * 2 + 1

        override fun state(
            c: IndicatorContext,
            i: Int,
            p: PresetParams,
        ): BarState {
            val period = p.int("period")
            val now = c.returnVolatility(period, i)
            val lowest = (i - period + 1..i).minOf { c.returnVolatility(period, it) }
            val squeezed = now <= lowest * 1.1
            val change = c.changePercent(i)
            return BarState(
                squeezed && change >= p.double("breakout"),
                change <= -p.double("breakout"),
                "변동성 ${"%.3f".format(now)}% (최근 최저 ${"%.3f".format(lowest)}%) · 직전 대비 ${pct(change)}",
            )
        }
    },
    STRONG_CLOSE(
        "강한 종가",
        "종가가 당일 (고가−저가) 범위의 위쪽 기준 이상이면 매수",
        "한투",
        listOf(ParamSpec("ratio", "범위 위치(0~1)", 0.8, 0.5, 1.0, 0.05)),
    ) {
        override fun requiredBars(p: PresetParams) = 2

        override fun state(
            c: IndicatorContext,
            i: Int,
            p: PresetParams,
        ): BarState {
            val position = c.dayRangePosition(i) ?: return BarState(false, false, "당일 고가 = 저가")
            return BarState(position >= p.double("ratio"), false, "당일 범위 위치 ${"%.2f".format(position)}")
        }
    },
    BREAKOUT_FAIL(
        "돌파 실패",
        "매도 전용. 최근 N봉 안에 전고점을 돌파했다가 그 고점에서 하락 기준만큼 밀리면 매도",
        "한투",
        listOf(ParamSpec("period", "전고점 기간(봉)", 20, 5, 120, 1), ParamSpec("drop", "하락 기준(%)", -0.5, -5, -0.1, 0.1)),
    ) {
        override fun requiredBars(p: PresetParams) = p.int("period") * 2

        override fun state(
            c: IndicatorContext,
            i: Int,
            p: PresetParams,
        ): BarState {
            val period = p.int("period")
            // 최근 period 봉 중 "그 앞 period 봉의 최고가"를 넘은 봉이 있었으면 돌파한 것이다.
            val breakout =
                (i - period + 1..i).filter { j -> c.candles[j].high.toDouble() > c.highest(j - period, j - 1) }
                    .maxOfOrNull { c.candles[it].high.toDouble() }
                    ?: return BarState(false, false, "최근 ${period}봉 안에 전고점 돌파 없음")
            val fromHigh = (c.close(i) / breakout - 1) * 100
            return BarState(false, fromHigh <= p.double("drop"), "돌파 고점 ${fmt(breakout)} 대비 ${pct(fromHigh)}")
        }
    },
    BAR_MOMENTUM(
        "N봉 수익률 모멘텀",
        "최근 N봉 수익률이 매수 기준을 넘으면 매수, 매도 기준 아래면 매도 (한투 \"모멘텀\"은 여러 종목의 순위라 단일 종목 수익률로 바꿈)",
        "한투 \"모멘텀\"을 바꿈",
        listOf(
            ParamSpec("period", "수익률 기간(봉)", 10, 2, 120, 1),
            ParamSpec("buy", "매수 기준(%)", 0.5, 0.05, 5, 0.05),
            ParamSpec("sell", "매도 기준(%)", -0.5, -5, -0.05, 0.05),
        ),
    ) {
        override fun requiredBars(p: PresetParams) = p.int("period") + 1

        override fun state(
            c: IndicatorContext,
            i: Int,
            p: PresetParams,
        ): BarState {
            val roc = (c.close(i) / c.close(i - p.int("period")) - 1) * 100
            return BarState(roc > p.double("buy"), roc < p.double("sell"), "${p.int("period")}봉 수익률 ${pct(roc)}")
        }
    },
    INTRADAY_HIGH_BREAKOUT(
        "당일 신고가 돌파",
        "종가가 그날 앞선 봉들의 최고가를 (마진만큼) 넘으면 매수 (한투 \"52주 신고가\"는 분봉에 52주가 없어 당일 기준으로 바꿈)",
        "한투 \"52주 신고가\"를 바꿈",
        listOf(ParamSpec("margin", "돌파 마진(%)", 0.0, 0.0, 2, 0.05)),
    ) {
        override fun requiredBars(p: PresetParams) = 2

        override fun state(
            c: IndicatorContext,
            i: Int,
            p: PresetParams,
        ): BarState {
            val high = c.dayHighBefore(i) ?: return BarState(false, false, "그날 첫 봉")
            val line = high * (1 + p.double("margin") / 100)
            return BarState(c.close(i) > line, false, "종가 ${fmt(c.close(i))} / 당일 앞선 고가 ${fmt(high)}")
        }
    },
    RSI_REBOUND(
        "RSI 과매도 반등",
        "RSI가 과매도 기준을 위로 넘으면 매수, 과매수 기준을 아래로 내려오면 매도",
        "우리 추가",
        listOf(
            ParamSpec("period", "RSI 기간(봉)", 14, 2, 60, 1),
            ParamSpec("low", "과매도 기준", 30, 5, 50, 1),
            ParamSpec("high", "과매수 기준", 70, 50, 95, 1),
        ),
    ) {
        override fun requiredBars(p: PresetParams) = p.int("period") + 1

        override fun state(
            c: IndicatorContext,
            i: Int,
            p: PresetParams,
        ): BarState {
            val rsi = c.rsi(p.int("period"), i)
            // 상태를 "기준 위/아래"로 두면 거짓→참 순간이 곧 상향·하향 돌파다.
            return BarState(rsi > p.double("low"), rsi < p.double("high"), "RSI${p.int("period")} ${"%.1f".format(rsi)}")
        }
    },
    MACD_CROSS(
        "MACD 교차",
        "MACD 선이 시그널선을 위로 뚫으면 매수, 아래로 뚫으면 매도",
        "우리 추가",
        listOf(
            ParamSpec("fast", "단기 EMA(봉)", 12, 2, 60, 1),
            ParamSpec("slow", "장기 EMA(봉)", 26, 5, 120, 1),
            ParamSpec("signal", "시그널(봉)", 9, 2, 30, 1),
        ),
    ) {
        override fun requiredBars(p: PresetParams) = p.int("slow") + p.int("signal")

        override fun state(
            c: IndicatorContext,
            i: Int,
            p: PresetParams,
        ): BarState {
            val macd = c.macd(p.int("fast"), p.int("slow"), i)
            val signal = c.macdSignal(p.int("fast"), p.int("slow"), p.int("signal"), i)
            return BarState(macd > signal, macd < signal, "MACD ${"%.2f".format(macd)} / 시그널 ${"%.2f".format(signal)}")
        }
    },
    BOLLINGER_REBOUND(
        "볼린저 하단 반등",
        "종가가 하단 밴드 위로 돌아오면 매수, 상단 밴드에 닿으면 매도",
        "우리 추가",
        listOf(ParamSpec("period", "기간(봉)", 20, 5, 120, 1), ParamSpec("k", "표준편차 배수", 2.0, 1.0, 3.0, 0.1)),
    ) {
        override fun requiredBars(p: PresetParams) = p.int("period")

        override fun state(
            c: IndicatorContext,
            i: Int,
            p: PresetParams,
        ): BarState {
            val lower = c.bollingerLower(p.int("period"), p.double("k"), i)
            val upper = c.bollingerUpper(p.int("period"), p.double("k"), i)
            return BarState(
                c.close(i) > lower,
                c.close(i) >= upper,
                "종가 ${fmt(c.close(i))} / 하단 ${fmt(lower)} · 상단 ${fmt(upper)}",
            )
        }
    }, ;

    /** 이만큼 봉이 쌓여야 조건을 본다. 모자라면 사지도 팔지도 않는다. */
    abstract fun requiredBars(p: PresetParams): Int

    abstract fun state(
        c: IndicatorContext,
        i: Int,
        p: PresetParams,
    ): BarState

    /** 매도 조건이 있는 프리셋인가(지표 매도 옵션을 켤 수 있는가). */
    val hasSell: Boolean get() = this !in setOf(STRONG_CLOSE, INTRADAY_HIGH_BREAKOUT)

    /** 매수 조건이 있는 프리셋인가(돌파 실패는 매도 전용). */
    val hasBuy: Boolean get() = this != BREAKOUT_FAIL
}

private fun fmt(value: Double): String = if (kotlin.math.abs(value) >= 100) "%,.0f".format(value) else "%,.2f".format(value)

private fun pct(value: Double): String = "%+.2f%%".format(value)

package com.quantlog.indicator

import com.quantlog.broker.MinuteCandle

/** 지표 전략 신호. 매수·매도는 조건이 거짓→참으로 바뀐 그 봉에서만 나온다. */
enum class IndicatorAction { BUY, SELL, HOLD }

/**
 * 분봉 목록을 한 프리셋으로 처음부터 끝까지 판정한 결과. states[i] 는 i 번째 봉의 조건 상태이고, 봉이 모자라면 null 이다.
 * 라이브·백테스트·화면 미리보기가 모두 이것을 쓴다 — 판정 코드가 한 곳이라 서로 어긋나지 않는다.
 */
class IndicatorTimeline(
    val candles: List<MinuteCandle>,
    val states: List<BarState?>,
) {
    /**
     * 조건이 직전 봉에서 거짓이었고 이번 봉에서 참이 됐을 때만 BUY/SELL(2026-11-04 사용자: "거짓→참으로 바뀔 때만").
     * 직전 봉 상태가 없으면(봉이 모자랐으면) 신호를 내지 않는다 — 처음 보는 순간 이미 참인 조건으로는 사지 않는다.
     * 매수·매도가 같은 봉에 동시에 켜지면 아무것도 하지 않는다.
     */
    fun actionAt(i: Int): IndicatorAction {
        val now = states.getOrNull(i) ?: return IndicatorAction.HOLD
        val previous = states.getOrNull(i - 1) ?: return IndicatorAction.HOLD
        val buy = now.buy && !previous.buy
        val sell = now.sell && !previous.sell
        return when {
            buy && !sell -> IndicatorAction.BUY
            sell && !buy -> IndicatorAction.SELL
            else -> IndicatorAction.HOLD
        }
    }
}

object IndicatorEvaluator {
    /** candlesAscending 는 오래된 것부터. 장 시작 직후 봉이 모자라지 않게 직전 거래일 분봉을 앞에 붙여 넘긴다. */
    fun timeline(
        preset: IndicatorPreset,
        params: PresetParams,
        candlesAscending: List<MinuteCandle>,
    ): IndicatorTimeline {
        val context = IndicatorContext(candlesAscending)
        val required = preset.requiredBars(params)
        val states =
            candlesAscending.indices.map { i ->
                if (i + 1 < required) null else preset.state(context, i, params)
            }
        return IndicatorTimeline(candlesAscending, states)
    }
}

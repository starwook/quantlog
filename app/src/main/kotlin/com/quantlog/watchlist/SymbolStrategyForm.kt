package com.quantlog.watchlist

import java.math.BigDecimal

/** 설정 화면 입력값. 비어 있는 칸도 받아서 [applyTo] 가 검증 메시지로 돌려준다. */
class SymbolStrategyForm {
    var takeProfitPercent: BigDecimal? = null
    var stopLossPercent: BigDecimal? = null
    var martingale: Boolean = false
    var martingaleDropPercent: BigDecimal? = null
    var martingaleMultiplier: Int? = null
    var martingaleMaxStages: Int? = null
    var etf: Boolean = false
    var supportBounceEntry: Boolean = false
    var periodicRebuy: Boolean = false
    var periodicRebuyQuantity: Int? = null
    var periodicRebuyIntervalMinutes: Int? = null
    var supportBounceQuantity: Int? = null

    /**
     * 입력값을 검증해 [target] 에 옮긴다. 값이 잘못됐으면 [IllegalArgumentException] (메시지는 화면에 그대로 보여준다).
     * 검증을 전부 통과하기 전에는 [target] 을 바꾸지 않는다.
     */
    fun applyTo(target: SymbolStrategy) {
        val takeProfit = positive(takeProfitPercent, "익절 %")
        val stopLoss = stopLossPercent?.also { positive(it, "손절 %") }
        val drop = positive(martingaleDropPercent, "마틴게일 추가매수 하락 %")
        // 마틴게일은 단계마다 평단이 -dropPercent 씩 내려가 추가 매수하는데, 손절이 그보다 작으면 첫 추가 매수 전에 손절돼 마틴게일이 성립하지 않는다.
        if (martingale && stopLoss != null) {
            require(stopLoss >= drop) {
                "마틴게일을 켠 종목은 손절 %(${stopLoss.plain()})가 추가매수 하락 %(${drop.plain()}) 이상이어야 합니다"
            }
        }
        val rebuyQuantity = atLeast(periodicRebuyQuantity, 1, "재매수 수량")
        val rebuyInterval = atLeast(periodicRebuyIntervalMinutes, 1, "재매수 간격(분)")
        val bounceQuantity = atLeast(supportBounceQuantity, 1, "저점 판단 진입 수량")
        val multiplier = atLeast(martingaleMultiplier, 2, "마틴게일 배수")
        val maxStages = atLeast(martingaleMaxStages, 1, "마틴게일 최대 단계")

        target.watchSymbol?.etf = etf
        target.takeProfitPercent = takeProfit
        target.stopLossPercent = stopLoss
        target.martingale = martingale
        target.supportBounceEntry = supportBounceEntry
        target.periodicRebuy = periodicRebuy
        target.periodicRebuyQuantity = rebuyQuantity
        target.periodicRebuyIntervalMinutes = rebuyInterval
        target.supportBounceQuantity = bounceQuantity
        target.martingaleDropPercent = drop
        target.martingaleMultiplier = multiplier
        target.martingaleMaxStages = maxStages
    }

    private fun BigDecimal.plain() = stripTrailingZeros().toPlainString()

    private fun atLeast(
        value: Int?,
        min: Int,
        name: String,
    ): Int {
        require(value != null && value >= min) { "$name 는 $min 이상의 정수여야 합니다" }
        return value
    }

    private fun positive(
        value: BigDecimal?,
        name: String,
    ): BigDecimal {
        require(value != null && value > BigDecimal.ZERO) { "$name 는 0보다 큰 숫자여야 합니다" }
        return value
    }
}

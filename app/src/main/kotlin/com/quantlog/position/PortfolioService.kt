package com.quantlog.position

import com.quantlog.broker.Market
import com.quantlog.broker.Side
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.MathContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private val KST: ZoneId = ZoneId.of("Asia/Seoul")
private val HUNDRED: BigDecimal = BigDecimal(100)

data class RealizedPnl(
    val amount: BigDecimal,
    val percent: BigDecimal,
    /** 이 매도의 체결 직전 평단(매수 평단). */
    val avgBuyPrice: BigDecimal,
    /** 손익을 확정한 수량(체결된 수량. 일부 체결 뒤 취소됐으면 주문 수량보다 적다). */
    val matchedQuantity: Int,
)

data class HoldingView(
    val market: Market,
    val symbol: String,
    val quantity: Int,
    val avgCost: BigDecimal,
    val currentPrice: BigDecimal,
    val value: BigDecimal,
    val unrealizedPnl: BigDecimal,
    val unrealizedPnlPercent: BigDecimal,
)

/** 통화 하나 기준의 계좌 요약. 통화가 다르면 절대 합산하지 않는다. */
data class PortfolioSummary(
    val currency: String,
    val holdings: List<HoldingView>,
    val holdingsValue: BigDecimal,
    val costBasis: BigDecimal,
    val unrealizedPnl: BigDecimal,
    val unrealizedPnlPercent: BigDecimal,
    val realizedPnlToday: BigDecimal,
    val realizedPnlTodayPercent: BigDecimal,
    val realizedPnlTotal: BigDecimal,
    val realizedPnlTotalPercent: BigDecimal,
    /** 오늘(KST) 접수된 매수·매도 주문 건수. 손익과 달리 체결 여부와 무관하게 [Trade] 기록 수 그대로 센다. */
    val buyCountToday: Int = 0,
    val sellCountToday: Int = 0,
    /** 오늘(KST) 실현손익이 계산된 매도 중 성공(손익 > 0)·실패(손익 <= 0, 본전 포함) 건수. 매수분과 매칭 안 된 매도는 제외. */
    val sellWinToday: Int = 0,
    val sellLossToday: Int = 0,
)

data class PortfolioSnapshot(
    val trades: List<Trade>,
    val realizedPnlByTradeId: Map<Long, RealizedPnl>,
    val summaryByCurrency: Map<String, PortfolioSummary>,
    /** 매도 주문별 체결 직전 평단(취소된 주문 포함 — 일부 체결 뒤 취소돼도 체결된 몫의 손익이 있다). 체결 내역 줄의 손익 계산에 쓴다. */
    val avgCostBeforeByOrder: Map<Pair<Market, String>, BigDecimal> = emptyMap(),
)

private class RealizedAcc {
    var totalAmount: BigDecimal = BigDecimal.ZERO
    var totalCost: BigDecimal = BigDecimal.ZERO
    var todayAmount: BigDecimal = BigDecimal.ZERO
    var todayCost: BigDecimal = BigDecimal.ZERO
    var winToday: Int = 0
    var lossToday: Int = 0
}

/**
 * 보유 현황은 잔고 테이블([AccountHolding])을 그대로 쓴다. 매도 손익은 매매 기록에 적어 둔 체결 직전 평단([Trade.avgCostBefore])으로
 * 확정한다. KIS 를 직접 부르지 않는다.
 */
@Service
class PortfolioService(
    private val tradeRepository: TradeRepository,
    private val accountHoldingRepository: AccountHoldingRepository,
    private val orderFills: OrderFills,
) {
    fun snapshot(): PortfolioSnapshot = buildSnapshot()

    private fun buildSnapshot(): PortfolioSnapshot {
        val allTrades = tradeRepository.findAll()
        val trades = allTrades.filterNot { it.canceled }.sortedBy { it.executedAt }
        // 체결 원장(broker_fill)에 체결이 반영된 주문만 손익·횟수에 넣는다. 일부만 체결된 뒤 취소됐어도 체결된 몫은 실제 거래라 넣는다.
        // 매도 손익은 그 매도의 체결 직전 평단으로 확정한다([realizedOf]). 미체결·확인 전 주문은 기록 화면에만 보인다.
        val ledgerOrders = orderFills.projectedAll().map { it.market to it.orderNo }.toSet()
        val filledTrades = allTrades.filter { (it.market to it.orderNo) in ledgerOrders }.sortedBy { it.executedAt }
        val today = Instant.now().atZone(KST).toLocalDate()

        val realizedByCurrency = mutableMapOf<String, RealizedAcc>()
        val pnlByTradeId = mutableMapOf<Long, RealizedPnl>()
        val buyCountTodayByCurrency = mutableMapOf<String, Int>()
        val sellCountTodayByCurrency = mutableMapOf<String, Int>()

        filledTrades.forEach { trade ->
            if (trade.executedAt.atZone(KST).toLocalDate() == today) {
                val counts = if (trade.side == Side.BUY) buyCountTodayByCurrency else sellCountTodayByCurrency
                counts.merge(trade.market.currency, 1, Int::plus)
            }
            if (trade.side == Side.SELL) realizedOf(trade)?.let { record(trade, it, realizedByCurrency, pnlByTradeId, today) }
        }

        val holdingsByCurrency = holdingViews()

        val currencies =
            (holdingsByCurrency.keys + realizedByCurrency.keys + buyCountTodayByCurrency.keys + sellCountTodayByCurrency.keys)
                .ifEmpty { setOf("KRW") }
        val summaryByCurrency =
            currencies.associateWith { currency ->
                val holdings = holdingsByCurrency[currency].orEmpty()
                val holdingsValue = holdings.sumOf { it.value }
                val costBasis = holdings.sumOf { it.avgCost.multiply(BigDecimal(it.quantity)) }
                val unrealizedPnl = holdingsValue.subtract(costBasis)
                val realized = realizedByCurrency[currency] ?: RealizedAcc()
                PortfolioSummary(
                    currency = currency,
                    holdings = holdings,
                    holdingsValue = holdingsValue,
                    costBasis = costBasis,
                    unrealizedPnl = unrealizedPnl,
                    unrealizedPnlPercent = percentOf(unrealizedPnl, costBasis),
                    realizedPnlToday = realized.todayAmount,
                    realizedPnlTodayPercent = percentOf(realized.todayAmount, realized.todayCost),
                    realizedPnlTotal = realized.totalAmount,
                    realizedPnlTotalPercent = percentOf(realized.totalAmount, realized.totalCost),
                    buyCountToday = buyCountTodayByCurrency[currency] ?: 0,
                    sellCountToday = sellCountTodayByCurrency[currency] ?: 0,
                    sellWinToday = realized.winToday,
                    sellLossToday = realized.lossToday,
                )
            }

        val avgCostBeforeByOrder =
            allTrades.mapNotNull { trade -> trade.avgCostBefore?.let { (trade.market to trade.orderNo) to it } }.toMap()
        return PortfolioSnapshot(trades, pnlByTradeId, summaryByCurrency, avgCostBeforeByOrder)
    }

    private class Realized(val amount: BigDecimal, val cost: BigDecimal, val quantity: Int)

    /** 매도 주문의 손익 = (평균 체결가 − 체결 직전 평단) × 체결수량. 평단이나 체결가를 모르면 null — 짐작으로 채우지 않는다. */
    private fun realizedOf(trade: Trade): Realized? {
        val average = trade.avgCostBefore ?: return null
        val price = trade.filledPrice ?: return null
        val quantity = trade.filledQuantity ?: trade.quantity
        if (quantity <= 0) return null
        val cost = average.multiply(BigDecimal(quantity))
        return Realized(price.multiply(BigDecimal(quantity)).subtract(cost), cost, quantity)
    }

    private fun record(
        trade: Trade,
        realized: Realized,
        realizedByCurrency: MutableMap<String, RealizedAcc>,
        pnlByTradeId: MutableMap<Long, RealizedPnl>,
        today: LocalDate,
    ) {
        val percent = percentOf(realized.amount, realized.cost)
        val avgBuyPrice = realized.cost.divide(BigDecimal(realized.quantity), MathContext.DECIMAL64)
        trade.id?.let { pnlByTradeId[it] = RealizedPnl(realized.amount, percent, avgBuyPrice, realized.quantity) }

        val acc = realizedByCurrency.getOrPut(trade.market.currency) { RealizedAcc() }
        acc.totalAmount = acc.totalAmount.add(realized.amount)
        acc.totalCost = acc.totalCost.add(realized.cost)
        if (trade.executedAt.atZone(KST).toLocalDate() == today) {
            acc.todayAmount = acc.todayAmount.add(realized.amount)
            acc.todayCost = acc.todayCost.add(realized.cost)
            if (realized.amount > BigDecimal.ZERO) acc.winToday++ else acc.lossToday++
        }
    }

    private fun holdingViews(): Map<String, List<HoldingView>> =
        accountHoldingRepository.findAll()
            .filter { it.quantity > 0 }
            .sortedWith(compareBy({ it.market }, { it.symbol }))
            .groupBy(
                { it.market.currency },
                { holdingView(it.market, it.symbol, it.quantity, it.avgCost, it.currentPrice) },
            )

    private fun holdingView(
        market: Market,
        symbol: String,
        quantity: Int,
        avgCost: BigDecimal,
        currentPrice: BigDecimal,
    ): HoldingView {
        val cost = avgCost.multiply(BigDecimal(quantity))
        val value = currentPrice.multiply(BigDecimal(quantity))
        val unrealized = value.subtract(cost)
        return HoldingView(market, symbol, quantity, avgCost, currentPrice, value, unrealized, percentOf(unrealized, cost))
    }

    private fun percentOf(
        amount: BigDecimal,
        base: BigDecimal,
    ): BigDecimal = if (base > BigDecimal.ZERO) amount.multiply(HUNDRED).divide(base, MathContext.DECIMAL64) else BigDecimal.ZERO
}

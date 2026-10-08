package com.quantlog.position

import com.quantlog.broker.Market
import com.quantlog.broker.Side
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.MathContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.ArrayDeque

private val KST: ZoneId = ZoneId.of("Asia/Seoul")
private val HUNDRED: BigDecimal = BigDecimal(100)

data class RealizedPnl(
    val amount: BigDecimal,
    val percent: BigDecimal,
    /** 이 매도와 FIFO 로 짝지어진 매수분의 평균 매수가. */
    val avgBuyPrice: BigDecimal,
    /** 매수분과 실제로 짝지어진 수량(매도 수량보다 적을 수 있다). */
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
)

private class Lot(var quantity: Int, val price: BigDecimal)

private class RealizedAcc {
    var totalAmount: BigDecimal = BigDecimal.ZERO
    var totalCost: BigDecimal = BigDecimal.ZERO
    var todayAmount: BigDecimal = BigDecimal.ZERO
    var todayCost: BigDecimal = BigDecimal.ZERO
    var winToday: Int = 0
    var lossToday: Int = 0
}

/**
 * 보유 현황은 잔고 테이블([AccountHolding], KIS 잔고 사본)을 그대로 쓰고, 실현손익은 원시 매매 기록(Trade)으로 FIFO 계산한다.
 * 파생값은 저장하지 않고 매번 다시 계산한다 — 기록 건수가 적어 성능 문제가 없고, 저장된 값이
 * 원본 기록과 어긋날 일도 없다. KIS 를 직접 부르지 않는다(동기화는 [HoldingSyncService] 가 주기적으로).
 */
@Service
class PortfolioService(
    private val tradeRepository: TradeRepository,
    private val accountHoldingRepository: AccountHoldingRepository,
    private val tradeFillRepository: TradeFillRepository,
) {
    fun snapshot(): PortfolioSnapshot = buildSnapshot()

    private fun buildSnapshot(): PortfolioSnapshot {
        val allTrades = tradeRepository.findAll()
        val trades = allTrades.filterNot { it.canceled }.sortedBy { it.executedAt }
        // 체결 원장이 있는 주문은 원장으로 손익을 확정하고, 없는 옛 주문만 FIFO 로 계산한다. 원장이 있는 주문은 일부만 체결된 뒤
        // 취소됐어도 체결된 몫은 실제 거래라 계산에 넣는다.
        val fillsByOrder = tradeFillRepository.findAll().groupBy { it.market to it.orderNo }
        val hasFills = { trade: Trade -> (trade.market to trade.orderNo) in fillsByOrder }
        // 체결가를 못 구한 주문(미체결이거나 확인 전)은 손익·횟수 계산에서 뺀다. 기록 화면에는 그대로 보인다.
        val filledTrades =
            allTrades.filter { (!it.canceled && it.filledPrice != null) || hasFills(it) }.sortedBy { it.executedAt }
        val today = Instant.now().atZone(KST).toLocalDate()

        val lotsByKey = mutableMapOf<Pair<Market, String>, ArrayDeque<Lot>>()
        val realizedByCurrency = mutableMapOf<String, RealizedAcc>()
        val pnlByTradeId = mutableMapOf<Long, RealizedPnl>()
        val buyCountTodayByCurrency = mutableMapOf<String, Int>()
        val sellCountTodayByCurrency = mutableMapOf<String, Int>()

        filledTrades.forEach { trade ->
            val key = trade.market to trade.symbol
            val lots = lotsByKey.getOrPut(key) { ArrayDeque() }
            if (trade.executedAt.atZone(KST).toLocalDate() == today) {
                val counts = if (trade.side == Side.BUY) buyCountTodayByCurrency else sellCountTodayByCurrency
                counts.merge(trade.market.currency, 1, Int::plus)
            }
            fillsByOrder[trade.market to trade.orderNo]?.let { fills ->
                if (trade.side == Side.SELL) realizedFromLedger(fills)?.let { record(trade, it, realizedByCurrency, pnlByTradeId, today) }
                return@forEach
            }
            // 실제 체결가가 있으면 그걸 쓴다 — 지정가와 다를 수 있다 (2026-09-29: 국내는 지정가·체결가가 꽤 벌어진 적 있었음).
            val price = trade.filledPrice!!
            when (trade.side) {
                Side.BUY -> lots.addLast(Lot(trade.quantity, price))
                Side.SELL -> {
                    var remaining = trade.quantity
                    var cost = BigDecimal.ZERO
                    var matchedQty = 0
                    while (remaining > 0 && lots.isNotEmpty()) {
                        val lot = lots.first()
                        val take = minOf(remaining, lot.quantity)
                        cost = cost.add(lot.price.multiply(BigDecimal(take)))
                        matchedQty += take
                        lot.quantity -= take
                        remaining -= take
                        if (lot.quantity == 0) lots.removeFirst()
                    }
                    if (matchedQty > 0) {
                        val amount = price.multiply(BigDecimal(matchedQty)).subtract(cost)
                        record(trade, Realized(amount, cost, matchedQty), realizedByCurrency, pnlByTradeId, today)
                    }
                }
            }
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

        return PortfolioSnapshot(trades, pnlByTradeId, summaryByCurrency)
    }

    private class Realized(val amount: BigDecimal, val cost: BigDecimal, val quantity: Int)

    /**
     * 체결 원장으로 이 매도 주문의 손익을 확정한다. 각 체결은 체결 직전 평단 기준이다([TradeFill.avgCostBefore]).
     * 평단을 모르는 체결(보유 사본에 종목이 없었던 매도)은 뺀다 — 짐작으로 채우지 않는다. 계산할 체결이 없으면 null.
     */
    private fun realizedFromLedger(fills: List<TradeFill>): Realized? {
        val known = fills.filter { it.avgCostBefore != null }
        if (known.isEmpty()) return null
        val quantity = known.sumOf { it.quantity }
        val cost = known.sumOf { it.avgCostBefore!!.multiply(BigDecimal(it.quantity)) }
        val proceeds = known.sumOf { it.price.multiply(BigDecimal(it.quantity)) }
        return Realized(proceeds.subtract(cost), cost, quantity)
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

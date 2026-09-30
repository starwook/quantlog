package com.quantlog.position

import com.quantlog.broker.BrokerClient
import com.quantlog.broker.Holding
import com.quantlog.broker.Market
import com.quantlog.broker.Side
import com.quantlog.trading.HoldingSyncService
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.MathContext
import java.time.Instant
import java.time.ZoneId
import java.util.ArrayDeque

private val log = KotlinLogging.logger {}
private val KST: ZoneId = ZoneId.of("Asia/Seoul")
private val HUNDRED: BigDecimal = BigDecimal(100)

data class RealizedPnl(val amount: BigDecimal, val percent: BigDecimal)

data class HoldingView(
    val market: Market,
    val symbol: String,
    val quantity: Int,
    val avgCost: BigDecimal,
    val currentPrice: BigDecimal,
    val priceStale: Boolean,
    val value: BigDecimal,
    val unrealizedPnl: BigDecimal,
    val unrealizedPnlPercent: BigDecimal,
)

/** 통화 하나 기준의 계좌 요약. 통화가 다르면 절대 합산하지 않는다 (원화·달러 섞지 않기). */
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
}

/**
 * 원시 매매 기록(Trade)만 갖고 FIFO로 실현손익·보유 현황을 계산한다.
 * 파생값은 저장하지 않고 매번 다시 계산한다 — 기록 건수가 적어 성능 문제가 없고, 저장된 값이
 * 원본 기록과 어긋날 일도 없다.
 */
@Service
class PortfolioService(
    private val tradeRepository: TradeRepository,
    private val broker: BrokerClient,
    private val holdingSync: HoldingSyncService,
) {
    /** 매매 판단용. 보유 현황도 우리 기록(DB)으로 계산한다 — 초당 요청 한도 때문에 REST 잔고를 부르지 않는다. */
    fun snapshot(): PortfolioSnapshot = buildSnapshot(accountHoldings = null)

    /**
     * 화면용. 보유 종목은 KIS 잔고가 정답이라 DB 기록과 무관하게 그대로 보여준다(DB를 비워도, 서버를 새로 띄워도 동일).
     * 실현손익·매매 내역만 DB 기록으로 계산한다. 캐시는 없다 — 화면 조회 한 번에 KIS 잔고 조회 2회(국내·해외).
     * 잔고를 받은 김에 봇 기록과 다른 물량(증권사 앱에서 직접 거래한 것)은 DB에 맞춘다([HoldingSyncService]).
     */
    fun accountSnapshot(): PortfolioSnapshot {
        val kis = holdingsFromAccount()
        if (kis != null) {
            runCatching { holdingSync.sync(kis) }.onFailure { log.warn(it) { "KIS 잔고 → 매매 기록 동기화 실패" } }
        }
        return buildSnapshot(accountHoldings = kis)
    }

    private fun buildSnapshot(accountHoldings: List<Holding>?): PortfolioSnapshot {
        val trades = tradeRepository.findAll().sortedBy { it.executedAt }
        val today = Instant.now().atZone(KST).toLocalDate()

        val lotsByKey = mutableMapOf<Pair<Market, String>, ArrayDeque<Lot>>()
        val realizedByCurrency = mutableMapOf<String, RealizedAcc>()
        val pnlByTradeId = mutableMapOf<Long, RealizedPnl>()
        val buyCountTodayByCurrency = mutableMapOf<String, Int>()
        val sellCountTodayByCurrency = mutableMapOf<String, Int>()

        trades.forEach { trade ->
            val key = trade.market to trade.symbol
            val lots = lotsByKey.getOrPut(key) { ArrayDeque() }
            // 실제 체결가가 있으면 그걸 쓴다 — 지정가와 다를 수 있다 (2026-09-29: 국내는 지정가·체결가가 꽤 벌어진 적 있었음).
            val price = trade.filledPrice ?: trade.orderPrice
            if (trade.executedAt.atZone(KST).toLocalDate() == today) {
                val counts = if (trade.side == Side.BUY) buyCountTodayByCurrency else sellCountTodayByCurrency
                counts.merge(trade.market.currency, 1, Int::plus)
            }
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
                        val proceeds = price.multiply(BigDecimal(matchedQty))
                        val amount = proceeds.subtract(cost)
                        val percent = percentOf(amount, cost)
                        trade.id?.let { pnlByTradeId[it] = RealizedPnl(amount, percent) }

                        val acc = realizedByCurrency.getOrPut(trade.market.currency) { RealizedAcc() }
                        acc.totalAmount = acc.totalAmount.add(amount)
                        acc.totalCost = acc.totalCost.add(cost)
                        if (trade.executedAt.atZone(KST).toLocalDate() == today) {
                            acc.todayAmount = acc.todayAmount.add(amount)
                            acc.todayCost = acc.todayCost.add(cost)
                        }
                    }
                }
            }
        }

        val holdingsByCurrency =
            accountHoldings?.let { holdingViewsOf(it) } ?: holdingsFromTrades(lotsByKey)

        val currencies =
            (holdingsByCurrency.keys + realizedByCurrency.keys + buyCountTodayByCurrency.keys + sellCountTodayByCurrency.keys)
                .ifEmpty { setOf("USD") }
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
                )
            }

        return PortfolioSnapshot(trades, pnlByTradeId, summaryByCurrency)
    }

    private fun holdingsFromTrades(lotsByKey: Map<Pair<Market, String>, ArrayDeque<Lot>>): Map<String, List<HoldingView>> {
        val holdingsByCurrency = mutableMapOf<String, MutableList<HoldingView>>()
        lotsByKey.forEach { (key, lots) ->
            val quantity = lots.sumOf { it.quantity }
            if (quantity <= 0) return@forEach
            val (market, symbol) = key
            val cost = lots.sumOf { it.price.multiply(BigDecimal(it.quantity)) }
            val avgCost = cost.divide(BigDecimal(quantity), MathContext.DECIMAL64)
            val (currentPrice, stale) =
                try {
                    broker.quote(market, symbol).price to false
                } catch (e: Exception) {
                    log.warn(e) { "현재가 조회 실패, 평단가로 대체: $market $symbol" }
                    avgCost to true
                }
            holdingsByCurrency.getOrPut(market.currency) { mutableListOf() }.add(
                holdingView(market, symbol, quantity, avgCost, currentPrice, stale),
            )
        }
        return holdingsByCurrency
    }

    /** KIS 잔고(국내 1회 + 해외 미국 전체 1회). 조회에 실패하면 null — 호출한 쪽이 DB 기록 기반으로 대체한다. */
    private fun holdingsFromAccount(): List<Holding>? =
        try {
            broker.holdings(Market.KR) + broker.holdings(Market.NASDAQ)
        } catch (e: Exception) {
            log.warn(e) { "KIS 잔고 조회 실패, 매매 기록 기반 보유 현황으로 대체" }
            null
        }

    private fun holdingViewsOf(holdings: List<Holding>): Map<String, List<HoldingView>> =
        holdings
            .filter { it.quantity > BigDecimal.ZERO }
            .groupBy(
                { it.market.currency },
                { holdingView(it.market, it.symbol, it.quantity.toInt(), it.averagePrice, it.currentPrice, stale = false) },
            )

    private fun holdingView(
        market: Market,
        symbol: String,
        quantity: Int,
        avgCost: BigDecimal,
        currentPrice: BigDecimal,
        stale: Boolean,
    ): HoldingView {
        val cost = avgCost.multiply(BigDecimal(quantity))
        val value = currentPrice.multiply(BigDecimal(quantity))
        val unrealized = value.subtract(cost)
        return HoldingView(market, symbol, quantity, avgCost, currentPrice, stale, value, unrealized, percentOf(unrealized, cost))
    }

    private fun percentOf(
        amount: BigDecimal,
        base: BigDecimal,
    ): BigDecimal = if (base > BigDecimal.ZERO) amount.multiply(HUNDRED).divide(base, MathContext.DECIMAL64) else BigDecimal.ZERO
}

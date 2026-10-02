package com.quantlog.broker.paper

import com.quantlog.broker.BrokerClient
import com.quantlog.broker.BuyingPower
import com.quantlog.broker.Holding
import com.quantlog.broker.Market
import com.quantlog.broker.MinuteCandle
import com.quantlog.broker.OrderReceipt
import com.quantlog.broker.OrderRequest
import com.quantlog.broker.Quote
import com.quantlog.broker.Side
import com.quantlog.broker.kis.KisMockBroker
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 주문을 증권사에 보내지 않고 로컬에서 체결시키는 구현체 (`quantlog.broker.type=paper`).
 * 시세·분봉만 [KisMockBroker] 에서 받는다. 모의계좌가 하나뿐이라 여러 봇을 동시에 못 돌려서 만들었다.
 *
 * 체결 모델: 호가를 모르므로 현재가 ± 1틱을 매도/매수 1호가로 본다. 매수는 `현재가+1틱`, 매도는 `현재가-1틱`에
 * 전량 즉시 체결되고, 지정가가 그보다 불리하면(안 체결될 가격이면) 접수 실패로 던진다 — 미체결 주문은 없다.
 * 증권사 수수료는 0이고, 제세금(국내 주식 거래세·미국 SEC fee, 국내 ETF 는 면제)은 매도 체결가에서 빼서
 * 실효 체결가로 기록하므로 실현손익에 자동 반영된다.
 * 현실보다 낙관적인 부분: 호가 잔량·부분체결·시세 지연은 모른다.
 */
@Primary
@Component
@ConditionalOnProperty(name = ["quantlog.broker.type"], havingValue = "paper")
class PaperBroker(
    private val market: KisMockBroker,
    private val orders: PaperOrderRepository,
    private val properties: PaperProperties,
    private val etfRegistry: EtfRegistry,
) : BrokerClient {
    override fun quote(
        market: Market,
        symbol: String,
    ): Quote = this.market.quote(market, symbol)

    override fun previousClose(
        market: Market,
        symbol: String,
    ): BigDecimal? = this.market.previousClose(market, symbol)

    override fun minuteCandles(
        market: Market,
        symbol: String,
        atTime: java.time.LocalTime,
    ): List<MinuteCandle> = this.market.minuteCandles(market, symbol, atTime)

    override fun filledPrice(
        market: Market,
        orderNo: String,
    ): BigDecimal? = orderNo.toLongOrNull()?.let { orders.findById(it).orElse(null) }?.fillPrice

    override fun buyingPower(
        market: Market,
        symbol: String,
        price: BigDecimal,
    ): BuyingPower {
        val cash = account(market).cash
        return BuyingPower(market.currency, cash, cash.divide(price, 0, RoundingMode.DOWN))
    }

    override fun holdings(market: Market): List<Holding> =
        account(market).positions.filterValues { it.quantity > 0 }.map { (key, position) ->
            val current = runCatching { quote(key.first, key.second).price }.getOrDefault(position.avgCost)
            Holding(key.first, key.second, key.second, BigDecimal(position.quantity), position.avgCost, current)
        }

    @Synchronized
    override fun placeOrder(order: OrderRequest): OrderReceipt {
        val quote = quote(order.market, order.symbol)
        val fillPrice =
            when (order.side) {
                Side.BUY -> quote.price.add(quote.tickSize).also { check(order.limitPrice >= it) { rejected(order, "매도 1호가 $it") } }
                Side.SELL -> quote.price.subtract(quote.tickSize).also { check(order.limitPrice <= it) { rejected(order, "매수 1호가 $it") } }
            }
        val effective = fillPrice.multiply(costMultiplier(order)).setScale(PRICE_SCALE, RoundingMode.HALF_UP)

        val account = account(order.market)
        when (order.side) {
            Side.BUY -> check(account.cash >= effective.multiply(BigDecimal(order.quantity))) { rejected(order, "현금 부족 (${account.cash})") }
            Side.SELL ->
                check((account.positions[order.market to order.symbol]?.quantity ?: 0) >= order.quantity) {
                    rejected(order, "보유 수량 부족")
                }
        }
        val saved = orders.save(PaperOrder(order.market, order.symbol, order.side, order.quantity, effective))
        return OrderReceipt(saved.id.toString(), "모킹 체결 @ $effective")
    }

    private fun rejected(
        order: OrderRequest,
        reason: String,
    ) = "모킹 주문 거부: ${order.market} ${order.symbol} ${order.side} x${order.quantity} @ ${order.limitPrice} — $reason"

    /** 제세금을 체결가에 반영하는 배수. 매수는 비용이 없고, 매도는 1-요율(국내 ETF 는 거래세 면제, 해외는 SEC fee). */
    private fun costMultiplier(order: OrderRequest): BigDecimal {
        val percent =
            when {
                order.side == Side.BUY -> BigDecimal.ZERO
                order.market.isOverseas -> properties.usSellSecFeePercent
                etfRegistry.isEtf(order.market, order.symbol) -> BigDecimal.ZERO
                else -> properties.krStockSellTaxPercent
            }
        return BigDecimal.ONE.subtract(percent.divide(HUNDRED))
    }

    private class Position(var quantity: Int, var avgCost: BigDecimal)

    private class Account(val cash: BigDecimal, val positions: Map<Pair<Market, String>, Position>)

    /** 기록을 처음부터 다시 계산한 현금·보유. 해외는 미국 3개 거래소가 한 계좌(통화 USD)다. */
    private fun account(market: Market): Account {
        val markets = if (market.isOverseas) Market.entries.filter { it.isOverseas } else listOf(Market.KR)
        var cash = if (market.isOverseas) properties.initialCashUsd else properties.initialCashKrw
        val positions = mutableMapOf<Pair<Market, String>, Position>()
        orders.findAllByMarketInOrderByIdAsc(markets).forEach { o ->
            val amount = o.fillPrice.multiply(BigDecimal(o.quantity))
            val position = positions.getOrPut(o.market to o.symbol) { Position(0, BigDecimal.ZERO) }
            if (o.side == Side.BUY) {
                val total = position.avgCost.multiply(BigDecimal(position.quantity)).add(amount)
                position.quantity += o.quantity
                position.avgCost = total.divide(BigDecimal(position.quantity), PRICE_SCALE, RoundingMode.HALF_UP)
                cash = cash.subtract(amount)
            } else {
                position.quantity -= o.quantity
                cash = cash.add(amount)
            }
        }
        return Account(cash, positions)
    }

    private companion object {
        const val PRICE_SCALE = 4
        val HUNDRED = BigDecimal(100)
    }
}

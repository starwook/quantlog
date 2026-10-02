package com.quantlog.trading

import com.quantlog.broker.BrokerClient
import com.quantlog.broker.Market
import com.quantlog.broker.OrderRequest
import com.quantlog.broker.Side
import com.quantlog.position.PortfolioService
import com.quantlog.position.TradeService
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.ZonedDateTime

private val log = KotlinLogging.logger {}

/**
 * 사용자가 관심종목 화면에서 직접 낸 수동 매수·매도. 자동 매매와 같은 방식(현재가보다 한 호가 불리한 지정가 — 매수는 위, 매도는 아래)으로
 * 즉시 체결되게 내고, 리스크 가드를 거친다(매수는 자본 배분·하루 손실 한도 포함). 규칙(익절·손절·auto_trade)과 무관하게 나가므로
 * 화면 쪽에서 확인 창을 거친 뒤에만 호출된다.
 */
@Service
class ManualOrderService(
    private val broker: BrokerClient,
    private val riskGuard: RiskGuard,
    private val tradeService: TradeService,
    private val portfolioService: PortfolioService,
) {
    /** 살 수 없으면 [IllegalStateException]/[RiskViolationException] (메시지는 화면에 그대로 보여준다). */
    fun buy(
        market: Market,
        symbol: String,
        quantity: Int,
        now: ZonedDateTime = ZonedDateTime.now(),
    ) {
        require(quantity > 0) { "수량은 1주 이상이어야 합니다" }
        check(market.isTradable(now)) { "지금은 $market 거래 시간이 아닙니다" }
        val quote = broker.quote(market, symbol)
        val request = OrderRequest(market, symbol, Side.BUY, quantity, quote.price.add(quote.tickSize))
        riskGuard.checkBuy(request)
        place(request, "수동 매수(화면): 현재 ${quote.price}")
    }

    /** 팔 수 없으면 [IllegalStateException] (메시지는 화면에 그대로 보여준다). */
    fun sell(
        market: Market,
        symbol: String,
        quantity: Int,
        now: ZonedDateTime = ZonedDateTime.now(),
    ) {
        require(quantity > 0) { "수량은 1주 이상이어야 합니다" }
        check(market.isTradable(now)) { "지금은 $market 거래 시간이 아닙니다" }
        val holding =
            portfolioService.snapshot().summaryByCurrency.values
                .flatMap { it.holdings }
                .firstOrNull { it.market == market && it.symbol == symbol && it.quantity > 0 }
        checkNotNull(holding) { "보유 중이 아닙니다: $symbol" }
        check(quantity <= holding.quantity) { "보유 수량(${holding.quantity}주)보다 많이 팔 수 없습니다" }

        val quote = broker.quote(market, symbol)
        val request = OrderRequest(market, symbol, Side.SELL, quantity, quote.price.subtract(quote.tickSize))
        riskGuard.check(request)
        place(request, "수동 매도(화면): 평단 ${holding.avgCost} → 현재 ${quote.price}")
    }

    private fun place(
        request: OrderRequest,
        reason: String,
    ) {
        val receipt = broker.placeOrder(request)
        Thread.sleep(FILL_CHECK_WAIT_MILLIS)
        val filledPrice =
            runCatching { broker.filledPrice(request.market, receipt.orderNo) }
                .onFailure { log.warn(it) { "[체결가 조회 실패] ${request.market} ${receipt.orderNo} — 지정가로 표시됨" } }
                .getOrNull()
        tradeService.record(request, receipt, reason, filledPrice)
        log.info { "[수동 주문] ${request.side} ${request.market} ${request.symbol} x${request.quantity} 주문번호=${receipt.orderNo}" }
    }

    private companion object {
        const val FILL_CHECK_WAIT_MILLIS = 2000L
    }
}

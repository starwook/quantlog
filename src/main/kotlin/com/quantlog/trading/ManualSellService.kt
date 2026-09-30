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
 * 사용자가 화면에서 누른 수동 전량 매도. 청산 스케줄러와 같은 방식(현재가보다 한 호가 낮은 지정가)으로 팔고,
 * 리스크 가드를 거친다. 규칙(익절·손절·auto_trade)과 무관하게 나가므로 화면 쪽에서 확인 창을 거친 뒤에만 호출된다.
 */
@Service
class ManualSellService(
    private val broker: BrokerClient,
    private val riskGuard: RiskGuard,
    private val tradeService: TradeService,
    private val portfolioService: PortfolioService,
) {
    /** 팔 수 없으면 [IllegalStateException] (메시지는 화면에 그대로 보여준다). 성공하면 판 수량을 돌려준다. */
    fun sellAll(
        market: Market,
        symbol: String,
        now: ZonedDateTime = ZonedDateTime.now(),
    ): Int {
        check(market.isTradable(now)) { "지금은 $market 거래 시간이 아닙니다" }
        val holding =
            portfolioService.snapshot().summaryByCurrency.values
                .flatMap { it.holdings }
                .firstOrNull { it.market == market && it.symbol == symbol && it.quantity > 0 }
        checkNotNull(holding) { "보유 중이 아닙니다: $symbol" }

        val quote = broker.quote(market, symbol)
        val request = OrderRequest(market, symbol, Side.SELL, holding.quantity, quote.price.subtract(quote.tickSize))
        riskGuard.check(request)
        val receipt = broker.placeOrder(request)
        Thread.sleep(FILL_CHECK_WAIT_MILLIS)
        val filledPrice =
            runCatching { broker.filledPrice(market, receipt.orderNo) }
                .onFailure { log.warn(it) { "[체결가 조회 실패] $market ${receipt.orderNo} — 지정가로 표시됨" } }
                .getOrNull()
        val reason = "수동 매도(화면): 평단 ${holding.avgCost} → 현재 ${quote.price}"
        tradeService.record(request, receipt, reason, filledPrice)
        log.info { "[수동 매도] $market $symbol x${request.quantity} @ ${request.limitPrice} 주문번호=${receipt.orderNo}" }
        return request.quantity
    }

    private companion object {
        const val FILL_CHECK_WAIT_MILLIS = 2000L
    }
}

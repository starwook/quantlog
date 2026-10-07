package com.quantlog.trading

import com.quantlog.broker.BrokerClient
import com.quantlog.broker.CancelRequest
import com.quantlog.broker.Market
import com.quantlog.broker.OrderRequest
import com.quantlog.broker.OrderStatus
import com.quantlog.broker.Quote
import com.quantlog.broker.Side
import com.quantlog.position.AccountHoldingRepository
import com.quantlog.position.TradeService
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.ZonedDateTime

private val log = KotlinLogging.logger {}

/**
 * 사용자가 관심종목 화면에서 직접 낸 수동 매수·매도. 가격을 지정하면 그 지정가로(체결 안 될 수 있다), 비우면 자동 매매와 같은 방식
 * (현재가보다 한 호가 불리한 지정가 — 매수는 위, 매도는 아래)으로 즉시 체결되게 내고, 리스크 가드를 거친다(매수는 자본 배분·하루 손실 한도 포함). 규칙(익절·손절·auto_trade)과 무관하게 나가므로
 * 화면 쪽에서 확인 창을 거친 뒤에만 호출된다.
 */
@Service
class ManualOrderService(
    private val broker: BrokerClient,
    private val riskGuard: RiskGuard,
    private val tradeService: TradeService,
    private val accountHoldingRepository: AccountHoldingRepository,
) {
    /** 살 수 없으면 [IllegalStateException]/[RiskViolationException] (메시지는 화면에 그대로 보여준다). [limitPrice] 가 null 이면 즉시 체결가. */
    fun buy(
        market: Market,
        symbol: String,
        quantity: Int,
        now: ZonedDateTime = ZonedDateTime.now(),
        limitPrice: BigDecimal? = null,
    ) {
        require(quantity > 0) { "수량은 1주 이상이어야 합니다" }
        check(market.isTradable(now)) { "지금은 $market 거래 시간이 아닙니다" }
        val quote = broker.quote(market, symbol)
        val request = OrderRequest(market, symbol, Side.BUY, quantity, limitPrice?.let { validTick(quote, it) } ?: quote.oneTickAbove())
        riskGuard.checkBuy(request)
        place(request, "수동 매수(화면): ${if (limitPrice != null) "지정가 $limitPrice / " else ""}현재 ${quote.price}")
    }

    /** 팔 수 없으면 [IllegalStateException] (메시지는 화면에 그대로 보여준다). [limitPrice] 가 null 이면 즉시 체결가. */
    fun sell(
        market: Market,
        symbol: String,
        quantity: Int,
        now: ZonedDateTime = ZonedDateTime.now(),
        limitPrice: BigDecimal? = null,
    ) {
        require(quantity > 0) { "수량은 1주 이상이어야 합니다" }
        check(market.isTradable(now)) { "지금은 $market 거래 시간이 아닙니다" }
        // 매매 기록 전체를 계산하는 스냅숏 대신 잔고 사본(종목당 1행)만 읽는다 — 수동 주문 응답을 가볍게 유지하려고.
        val holding = accountHoldingRepository.findByMarketAndSymbol(market, symbol)?.takeIf { it.quantity > 0 }
        checkNotNull(holding) { "보유 중이 아닙니다: $symbol" }
        check(quantity <= holding.quantity) { "보유 수량(${holding.quantity}주)보다 많이 팔 수 없습니다" }

        val quote = broker.quote(market, symbol)
        val request = OrderRequest(market, symbol, Side.SELL, quantity, limitPrice?.let { validTick(quote, it) } ?: quote.oneTickBelow())
        riskGuard.check(request)
        place(request, "수동 매도(화면): 평단 ${holding.avgCost} → ${if (limitPrice != null) "지정가 $limitPrice / " else ""}현재 ${quote.price}")
    }

    /** 미체결 주문 취소. 이미 체결됐거나 취소됐으면 [IllegalStateException] (메시지는 화면에 그대로 보여준다). */
    fun cancel(tradeId: Long) {
        val trade = tradeService.find(tradeId)
        check(!trade.canceled) { "이미 취소된 주문입니다" }
        check(trade.filledPrice == null) { "이미 체결된 주문이라 취소할 수 없습니다" }
        // 화면의 상태가 낡았을 수 있으니 취소 직전에 증권사에 다시 물어서, 미체결이 확인된 주문만 취소한다.
        val status = broker.orderStatus(trade.market, trade.orderNo, trade.quantity)
        tradeService.applyStatus(trade, status)
        check(status !is OrderStatus.Filled) { "이미 체결된 주문이라 취소할 수 없습니다" }
        check(status == OrderStatus.Open) { "증권사가 아직 이 주문을 확인해 주지 않았습니다 — 잠시 뒤 다시 시도하세요" }
        check(trade.market.isOverseas || trade.branchNo != null) { "주문조직번호가 없는 옛 주문이라 취소할 수 없습니다 — 증권사 앱에서 취소하세요" }
        broker.cancelOrder(
            CancelRequest(trade.market, trade.symbol, trade.orderNo, trade.branchNo.orEmpty(), trade.quantity, trade.orderPrice),
        )
        tradeService.markCanceled(trade)
        log.info { "[수동 취소] ${trade.market} ${trade.symbol} 주문번호=${trade.orderNo}" }
    }

    /** 호가 단위에 안 맞는 가격은 증권사가 거부하므로 미리 막고 안내한다. */
    private fun validTick(
        quote: Quote,
        price: BigDecimal,
    ): BigDecimal {
        check(price > BigDecimal.ZERO && quote.roundToTick(price).compareTo(price) == 0) {
            "가격은 호가 단위(${quote.tickSize.stripTrailingZeros().toPlainString()})의 배수여야 합니다: ${price.stripTrailingZeros().toPlainString()}"
        }
        return price
    }

    private fun place(
        request: OrderRequest,
        reason: String,
    ) {
        val receipt = broker.placeOrder(request)
        // 체결가 조회를 기다리지 않는다(자동 청산과 같다) — 체결통보가 이미 왔으면 TradeService 가 보관한 체결가를 채우고, 아니면 통보가 올 때 채운다.
        tradeService.record(request, receipt, reason)
        log.info { "[수동 주문] ${request.side} ${request.market} ${request.symbol} x${request.quantity} 주문번호=${receipt.orderNo}" }
    }
}

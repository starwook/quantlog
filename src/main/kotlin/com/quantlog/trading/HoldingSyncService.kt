package com.quantlog.trading

import com.quantlog.broker.BrokerClient
import com.quantlog.broker.Holding
import com.quantlog.broker.Market
import com.quantlog.broker.OrderReceipt
import com.quantlog.broker.OrderRequest
import com.quantlog.broker.Side
import com.quantlog.position.PortfolioService
import com.quantlog.position.TradeService
import com.quantlog.watchlist.WatchedSymbol
import com.quantlog.watchlist.displayNameOf
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

private val log = KotlinLogging.logger {}

/**
 * 증권사 앱에서 직접 사고판 물량을 봇 기록(매매 DB)에 맞춘다. 봇은 보유 현황·평단·마틴게일 사이클을 자기 매매 기록으로 계산하는데,
 * 앱에서 직접 거래하면 그 기록에 없다. 사용자가 화면 버튼을 눌렀을 때만 실행한다 — 자동으로 돌리면 봇이 방금 낸 주문이
 * 기록되기 전에 잔고만 먼저 바뀐 순간을 "직접 거래"로 오해해 이중 기록할 수 있다.
 *
 * KIS 잔고(수량·평단)가 정답이다. 차이만큼 SYNC 주문번호의 가상 매매를 남긴다.
 * - 더 많으면 BUY: 가격은 "KIS 총매입금 − 봇이 아는 매입금"을 차이 수량으로 나눈 값이라 봇+KIS 평단이 KIS 평단과 같아진다.
 * - 더 적으면 SELL: 실제 매도가는 알 수 없어 현재가로 추정하고, 사유에 추정이라고 적는다.
 * 감시 종목([WatchedSymbol])만 맞춘다 — 봇과 무관한 종목은 건드리지 않는다.
 */
@Service
class HoldingSyncService(
    private val broker: BrokerClient,
    private val tradeService: TradeService,
    private val portfolioService: PortfolioService,
) {
    /** 맞춘 결과 설명 목록(차이가 없으면 빈 목록). 시장 하나가 실패해도 나머지는 계속한다. */
    fun sync(): List<String> {
        val known =
            portfolioService.snapshot().summaryByCurrency.values
                .flatMap { it.holdings }
                .associateBy { it.market to it.symbol }
        return WatchedSymbol.entries
            .map { it.market }
            .distinct()
            .flatMap { market ->
                val kis =
                    runCatching {
                        broker.holdings(
                            market,
                        )
                    }.getOrElse { throw IllegalStateException("$market 잔고 조회 실패: ${it.message}") }
                WatchedSymbol.entries
                    .filter { it.market == market }
                    .mapNotNull { watched ->
                        val actual = kis.firstOrNull { it.market == market && it.symbol == watched.symbol }
                        val bot = known[market to watched.symbol]
                        syncOne(market, watched.symbol, actual, bot?.quantity ?: 0, bot?.avgCost ?: BigDecimal.ZERO)
                    }
            }
    }

    private fun syncOne(
        market: Market,
        symbol: String,
        actual: Holding?,
        botQuantity: Int,
        botAvg: BigDecimal,
    ): String? {
        val actualQuantity = actual?.quantity?.toInt() ?: 0
        val diff = actualQuantity - botQuantity
        if (diff == 0) return null
        val name = displayNameOf(market, symbol)
        val orderNo = "SYNC-${System.currentTimeMillis()}"
        if (diff > 0) {
            val actualCost = actual!!.averagePrice.multiply(BigDecimal(actualQuantity))
            val price =
                actualCost.subtract(botAvg.multiply(BigDecimal(botQuantity)))
                    .divide(BigDecimal(diff), MathContext.DECIMAL64)
                    .takeIf { it > BigDecimal.ZERO } ?: actual.averagePrice
            val rounded = price.setScale(PRICE_SCALE, RoundingMode.HALF_UP)
            record(market, symbol, Side.BUY, diff, rounded, orderNo, "KIS 잔고 동기화: 앱에서 직접 매수한 ${diff}주 (KIS 평단 ${actual.averagePrice})")
            return "$name 매수 ${diff}주 반영 (평단 ${actual.averagePrice})"
        }
        val estimate = broker.quote(market, symbol).price
        record(market, symbol, Side.SELL, -diff, estimate, orderNo, "KIS 잔고 동기화: 앱에서 직접 매도한 ${-diff}주 (매도가는 현재가로 추정)")
        return "$name 매도 ${-diff}주 반영 (매도가는 현재가로 추정)"
    }

    private fun record(
        market: Market,
        symbol: String,
        side: Side,
        quantity: Int,
        price: BigDecimal,
        orderNo: String,
        reason: String,
    ) {
        tradeService.record(OrderRequest(market, symbol, side, quantity, price), OrderReceipt(orderNo, "잔고 동기화"), reason, price)
        log.info { "[잔고 동기화] $market $symbol $side x$quantity @ $price — $reason" }
    }

    private companion object {
        const val PRICE_SCALE = 4
    }
}

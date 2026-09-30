package com.quantlog.trading

import com.quantlog.broker.BrokerClient
import com.quantlog.broker.Holding
import com.quantlog.broker.Market
import com.quantlog.broker.OrderReceipt
import com.quantlog.broker.OrderRequest
import com.quantlog.broker.Side
import com.quantlog.position.Trade
import com.quantlog.position.TradeService
import com.quantlog.watchlist.WatchedSymbol
import com.quantlog.watchlist.displayNameOf
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant

private val log = KotlinLogging.logger {}

/**
 * KIS 잔고를 조회한 김에 그 값을 봇 매매 기록(DB)에 맞춘다. 증권사 앱에서 직접 사고판 물량은 봇 기록에 없어서
 * 평단·마틴게일 사이클이 어긋나기 때문이다(2026-09-30 사용자 결정: "잔고 조회는 자주 안 하지만 했을 때 그 값을 DB에 동기화").
 * 잔고 조회가 일어나는 곳(지금은 계좌 화면)에서 [sync] 를 부른다 — 별도 스케줄러는 없다.
 *
 * KIS 잔고(수량·평단)가 정답이고, 차이만큼 SYNC 주문번호의 가상 매매를 남긴다.
 * - KIS 가 더 많으면 BUY: 가격은 "KIS 총매입금 − 봇이 아는 매입금"을 차이 수량으로 나눈 값이라 합치면 KIS 평단과 같아진다.
 * - KIS 가 더 적으면 SELL: 실제 매도가는 알 수 없어 현재가로 추정하고 사유에 추정이라고 적는다.
 *   단 그 종목의 마지막 매매가 [RECENT] 이내면 건너뛴다 — 봇이 낸 주문이 아직 체결 전(미체결)이라 잔고에 없는 것일 수 있다.
 * 감시 종목([WatchedSymbol])만 맞춘다 — 봇과 무관한 종목은 건드리지 않는다.
 */
@Service
class HoldingSyncService(
    private val broker: BrokerClient,
    private val tradeService: TradeService,
) {
    /** 맞춘 내용 설명 목록(차이가 없으면 빈 목록). */
    fun sync(
        kis: List<Holding>,
        now: Instant = Instant.now(),
    ): List<String> =
        WatchedSymbol.entries.mapNotNull { watched ->
            val actual = kis.firstOrNull { it.market == watched.market && it.symbol == watched.symbol }
            runCatching { syncOne(watched.market, watched.symbol, actual, now) }
                .onFailure { log.warn(it) { "[잔고 동기화] 실패: ${watched.market} ${watched.symbol}" } }
                .getOrNull()
        }

    private class Lot(var quantity: Int, val price: BigDecimal)

    private fun syncOne(
        market: Market,
        symbol: String,
        actual: Holding?,
        now: Instant,
    ): String? {
        val trades = tradeService.trades(market, symbol)
        val lots = fifoLots(trades)
        val botQuantity = lots.sumOf { it.quantity }
        val botCost = lots.sumOf { it.price.multiply(BigDecimal(it.quantity)) }
        val actualQuantity = actual?.quantity?.toInt() ?: 0
        val diff = actualQuantity - botQuantity
        if (diff == 0) return null
        val name = displayNameOf(market, symbol)
        val orderNo = "SYNC-${now.toEpochMilli()}"

        if (diff > 0) {
            val average = actual!!.averagePrice
            val price =
                average.multiply(BigDecimal(actualQuantity)).subtract(botCost)
                    .divide(BigDecimal(diff), MathContext.DECIMAL64)
                    .takeIf { it > BigDecimal.ZERO } ?: average
            record(market, symbol, Side.BUY, diff, price, orderNo, "KIS 잔고 동기화: 봇이 모르는 매수 ${diff}주 (KIS 평단 $average)")
            return "$name 매수 ${diff}주 반영 (평단 $average)"
        }

        val lastTradeAt = trades.maxOfOrNull { it.executedAt }
        if (lastTradeAt != null && Duration.between(lastTradeAt, now) < RECENT) return null
        val estimate = broker.quote(market, symbol).price
        record(market, symbol, Side.SELL, -diff, estimate, orderNo, "KIS 잔고 동기화: 봇이 모르는 매도 ${-diff}주 (매도가는 현재가로 추정)")
        return "$name 매도 ${-diff}주 반영 (매도가는 현재가로 추정)"
    }

    /** 매매 기록(체결 시각 오름차순)으로 남은 FIFO 로트를 계산한다 — 화면 손익 계산(PortfolioService)과 같은 방식. */
    private fun fifoLots(trades: List<Trade>): List<Lot> {
        val lots = ArrayDeque<Lot>()
        trades.forEach { trade ->
            val price = trade.filledPrice ?: trade.orderPrice
            if (trade.side == Side.BUY) {
                lots.addLast(Lot(trade.quantity, price))
                return@forEach
            }
            var remaining = trade.quantity
            while (remaining > 0 && lots.isNotEmpty()) {
                val take = minOf(remaining, lots.first().quantity)
                lots.first().quantity -= take
                remaining -= take
                if (lots.first().quantity == 0) lots.removeFirst()
            }
        }
        return lots
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
        val rounded = price.setScale(PRICE_SCALE, RoundingMode.HALF_UP)
        tradeService.record(OrderRequest(market, symbol, side, quantity, rounded), OrderReceipt(orderNo, "잔고 동기화"), reason, rounded)
        log.info { "[잔고 동기화] $market $symbol $side x$quantity @ $rounded — $reason" }
    }

    private companion object {
        const val PRICE_SCALE = 4
        val RECENT: Duration = Duration.ofMinutes(10)
    }
}

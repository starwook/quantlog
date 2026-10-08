package com.quantlog.trading

import com.quantlog.broker.BrokerClient
import com.quantlog.broker.Holding
import com.quantlog.broker.Market
import com.quantlog.broker.OrderRequest
import com.quantlog.broker.Quote
import com.quantlog.broker.Side
import com.quantlog.marketdata.MarketDataService
import com.quantlog.position.TradeService
import com.quantlog.strategy.FixedPercentExitRule
import mu.KotlinLogging
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalTime
import java.time.ZoneId

private val log = KotlinLogging.logger {}

@ConfigurationProperties(prefix = "quantlog.smoke")
data class SmokeProperties(
    /** 비어 있으면 아무것도 하지 않는다. READ(조회만) / BUY / SELL. */
    val mode: String = "",
    val market: Market = Market.KR,
    val symbol: String = "005930",
    val quantity: Int = 1,
    /** 체결이 잘 되도록 현재가에서 벗어나는 지정가 폭(%). BUY 는 +, SELL 은 -. */
    val limitOffsetPercent: BigDecimal = BigDecimal("0.5"),
    /** 지정하면 현재가+폭 대신 이 가격으로 주문한다. 국내는 호가 단위에 맞는 가격이어야 한다. */
    val limitPrice: BigDecimal? = null,
)

/**
 * 모의투자 연동 점검용 1회성 실행기. 환경변수 QUANTLOG_SMOKE_MODE 로 켠다 (README 참조).
 * READ: 현재가/매수가능/잔고 조회만. BUY/SELL: 리스크 가드를 통과한 소량 지정가 주문 1건.
 */
@Component
class SmokeTestRunner(
    private val broker: BrokerClient,
    private val riskGuard: RiskGuard,
    private val properties: SmokeProperties,
    private val exitRule: FixedPercentExitRule,
    private val tradeService: TradeService,
    private val marketDataService: MarketDataService,
) : ApplicationRunner {
    /**
     * 점검용 1회성 실행기라 여기서 실패해도 앱 전체(청산 스케줄러 등)를 죽이면 안 된다 —
     * 2026-09-29: mode 를 안 지우고 재시작했다가 다른 스케줄러들과 KIS 호출이 겹쳐 초당 요청 한도에
     * 걸렸는데, 이 실행기가 예외를 던져서 ApplicationRunner 실패로 앱 전체가 기동에 실패했다.
     */
    override fun run(args: ApplicationArguments) {
        runCatching { doRun() }
            .onFailure { log.warn(it) { "[점검 실행기 실패] 점검만 실패했고 앱은 계속 돈다." } }
    }

    private fun doRun() {
        val mode = properties.mode.trim().uppercase()
        if (mode.isEmpty()) {
            log.info { "quantlog.smoke.mode 가 비어 있어 점검을 건너뜁니다." }
            return
        }
        if (mode == "CANDLES") {
            fetchCandles(properties.market, properties.symbol)
            return
        }
        val market = properties.market
        val symbol = properties.symbol

        val quote = broker.quote(market, symbol)
        log.info { "[현재가] $market $symbol = ${quote.price} ${market.currency} (호가 단위 ${quote.tickSize})" }

        val power = broker.buyingPower(market, symbol, quote.price)
        log.info { "[매수가능] ${power.orderableAmount} ${power.currency}, 최대 ${power.maxQuantity}주" }

        val holdingsBefore = logHoldings(market)

        when (mode) {
            "READ" -> Unit
            "BUY" -> order(Side.BUY, quote, BUY_REASON)
            "SELL" -> order(Side.SELL, quote, sellReason(holdingsBefore, symbol, quote))
            else -> error("알 수 없는 mode: $mode (READ/BUY/SELL/CANDLES)")
        }
    }

    /** 최근 분봉을 받아 DB에 새로 생긴 것만 저장한다. */
    private fun fetchCandles(
        market: Market,
        symbol: String,
    ) {
        val now = LocalTime.now(ZoneId.of("Asia/Seoul"))
        val saved = marketDataService.fetchAndStoreRecentMinutes(market, symbol, now)
        saved.sortedBy { it.tradeTime }.forEach {
            log.info { "[분봉] ${it.tradeTime} O=${it.open} H=${it.high} L=${it.low} C=${it.close} V=${it.volume}" }
        }
    }

    /** AI 진입 판단 루프가 없으니 지어내지 않는다: 매도는 실제로 걸려 있는 청산 규칙을 그대로 사유로 쓴다. */
    private fun sellReason(
        holdings: List<Holding>,
        symbol: String,
        quote: Quote,
    ): String {
        val holding = holdings.firstOrNull { it.symbol == symbol } ?: return MANUAL_SELL_REASON
        if (holding.averagePrice <= BigDecimal.ZERO) return MANUAL_SELL_REASON
        val changePercent =
            quote.price.subtract(holding.averagePrice)
                .multiply(BigDecimal(100))
                .divide(holding.averagePrice, MathContext.DECIMAL64)
        return "청산 규칙 판정: ${exitSummary(holding.averagePrice, quote)} " +
            "(평단 ${holding.averagePrice} → 현재 ${quote.price}, ${changePercent.setScale(2, RoundingMode.HALF_UP)}%)"
    }

    /** 체결이 잘 되도록 현재가에서 약간 불리한 쪽 호가로 낸다: 매수는 올림, 매도는 내림. */
    private fun order(
        side: Side,
        quote: Quote,
        reason: String,
    ) {
        val offset = properties.limitOffsetPercent.movePointLeft(2)
        val limitPrice =
            properties.limitPrice ?: when (side) {
                Side.BUY -> quote.roundToTick(quote.price.multiply(BigDecimal.ONE.add(offset)), RoundingMode.CEILING)
                Side.SELL -> quote.roundToTick(quote.price.multiply(BigDecimal.ONE.subtract(offset)), RoundingMode.FLOOR)
            }
        val request = OrderRequest(properties.market, properties.symbol, side, properties.quantity, limitPrice)
        when (side) {
            Side.BUY -> riskGuard.checkBuy(request)
            Side.SELL -> riskGuard.check(request)
        }
        log.info { "[주문] $side ${request.symbol} x${request.quantity} @ ${request.limitPrice} ${request.market.currency}" }
        val receipt = broker.placeOrder(request)
        log.info { "[주문접수] 주문번호=${receipt.orderNo} (${receipt.message})" }
        Thread.sleep(SETTLE_WAIT_MILLIS)
        val filledPrice = fetchFilledPrice(request.market, receipt.orderNo)
        tradeService.record(request, receipt, reason, filledPrice)
        logHoldings(properties.market)
    }

    /** 조회 실패해도 주문 기록 자체는 남겨야 하니 지정가로 폴백하고 경고만 남긴다. */
    private fun fetchFilledPrice(
        market: Market,
        orderNo: String,
    ): BigDecimal? =
        runCatching { broker.filledPrice(market, orderNo) }
            .onFailure { log.warn(it) { "[체결가 조회 실패] $market $orderNo — 지정가로 표시됨" } }
            .getOrNull()

    private fun logHoldings(market: Market): List<Holding> {
        val holdings = broker.holdings(market)
        if (holdings.isEmpty()) {
            log.info { "[잔고] 보유 종목 없음 ($market)" }
            return holdings
        }
        holdings.forEach { h ->
            val summary =
                if (h.averagePrice > BigDecimal.ZERO) exitSummary(h.averagePrice, broker.quote(h.market, h.symbol)) else "N/A"
            log.info { "[잔고] ${h.market} ${h.symbol} ${h.quantity}주 평단=${h.averagePrice} 현재=${h.currentPrice} → 청산 규칙 판정: $summary" }
        }
        return holdings
    }

    private fun exitSummary(
        averagePrice: BigDecimal,
        quote: Quote,
    ): String {
        val targets = exitRule.targets(averagePrice, quote)
        return "${exitRule.evaluate(averagePrice, quote)} (익절 ${targets.takeProfitPrice} / 손절 ${targets.stopLossPrice})"
    }

    private companion object {
        const val SETTLE_WAIT_MILLIS = 3000L
        const val BUY_REASON = "수동 점검 매수 (SmokeTestRunner) — AI 진입 판단 루프는 아직 없음"
        const val MANUAL_SELL_REASON = "수동 점검 매도 (SmokeTestRunner)"
    }
}

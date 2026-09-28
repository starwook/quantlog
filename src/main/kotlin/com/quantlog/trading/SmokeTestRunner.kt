package com.quantlog.trading

import com.quantlog.broker.BrokerClient
import com.quantlog.broker.Holding
import com.quantlog.broker.Market
import com.quantlog.broker.OrderRequest
import com.quantlog.broker.Side
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

private val log = KotlinLogging.logger {}

@ConfigurationProperties(prefix = "quantlog.smoke")
data class SmokeProperties(
    /** 비어 있으면 아무것도 하지 않는다. READ(조회만) / BUY / SELL. */
    val mode: String = "",
    val market: Market = Market.NASDAQ,
    val symbol: String = "AAPL",
    val quantity: Int = 1,
    /** 체결이 잘 되도록 현재가에서 벗어나는 지정가 폭(%). BUY 는 +, SELL 은 -. */
    val limitOffsetPercent: BigDecimal = BigDecimal("0.5"),
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
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        val mode = properties.mode.trim().uppercase()
        if (mode.isEmpty()) {
            log.info { "quantlog.smoke.mode 가 비어 있어 점검을 건너뜁니다." }
            return
        }
        val market = properties.market
        val symbol = properties.symbol

        val price = broker.currentPrice(market, symbol)
        log.info { "[현재가] $market $symbol = $price ${market.currency}" }

        val power = broker.buyingPower(market, symbol, price)
        log.info { "[매수가능] ${power.orderableAmount} ${power.currency}, 최대 ${power.maxQuantity}주" }

        val holdingsBefore = logHoldings(market)

        when (mode) {
            "READ" -> Unit
            "BUY" -> order(Side.BUY, price, BUY_REASON)
            "SELL" -> order(Side.SELL, price, sellReason(holdingsBefore, symbol, price))
            else -> error("알 수 없는 mode: $mode (READ/BUY/SELL)")
        }
    }

    /** AI 진입 판단 루프가 없으니 지어내지 않는다: 매도는 실제로 걸려 있는 청산 규칙을 그대로 사유로 쓴다. */
    private fun sellReason(
        holdings: List<Holding>,
        symbol: String,
        currentPrice: BigDecimal,
    ): String {
        val holding = holdings.firstOrNull { it.symbol == symbol } ?: return MANUAL_SELL_REASON
        if (holding.averagePrice <= BigDecimal.ZERO) return MANUAL_SELL_REASON
        val signal = exitRule.evaluate(holding.averagePrice, currentPrice)
        val changePercent =
            currentPrice.subtract(holding.averagePrice)
                .multiply(BigDecimal(100))
                .divide(holding.averagePrice, MathContext.DECIMAL64)
        return "청산 규칙 판정: $signal (평단 ${holding.averagePrice} → 현재 $currentPrice, ${changePercent.setScale(2, RoundingMode.HALF_UP)}%)"
    }

    private fun order(
        side: Side,
        price: BigDecimal,
        reason: String,
    ) {
        val factor =
            BigDecimal.ONE.add(
                properties.limitOffsetPercent.movePointLeft(2).let { if (side == Side.BUY) it else it.negate() },
            )
        val request =
            OrderRequest(properties.market, properties.symbol, side, properties.quantity, price.multiply(factor))
        riskGuard.check(request)
        log.info { "[주문] $side ${request.symbol} x${request.quantity} @ ${request.limitPrice} ${request.market.currency}" }
        val receipt = broker.placeOrder(request)
        log.info { "[주문접수] 주문번호=${receipt.orderNo} (${receipt.message})" }
        tradeService.record(request, receipt, reason)
        Thread.sleep(SETTLE_WAIT_MILLIS)
        logHoldings(properties.market)
    }

    private fun logHoldings(market: Market): List<Holding> {
        val holdings = broker.holdings(market)
        if (holdings.isEmpty()) {
            log.info { "[잔고] 보유 종목 없음 ($market)" }
            return holdings
        }
        holdings.forEach { h ->
            val signal =
                if (h.averagePrice > BigDecimal.ZERO) exitRule.evaluate(h.averagePrice, h.currentPrice) else "N/A"
            log.info { "[잔고] ${h.market} ${h.symbol} ${h.quantity}주 평단=${h.averagePrice} 현재=${h.currentPrice} → 청산 규칙 판정: $signal" }
        }
        return holdings
    }

    private companion object {
        const val SETTLE_WAIT_MILLIS = 3000L
        const val BUY_REASON = "수동 점검 매수 (SmokeTestRunner) — AI 진입 판단 루프는 아직 없음"
        const val MANUAL_SELL_REASON = "수동 점검 매도 (SmokeTestRunner)"
    }
}

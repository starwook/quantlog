package com.quantlog.trading

import com.quantlog.broker.BrokerClient
import com.quantlog.broker.Market
import com.quantlog.broker.OrderRequest
import com.quantlog.broker.Side
import com.quantlog.strategy.FixedPercentExitRule
import mu.KotlinLogging
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.math.BigDecimal

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

        logHoldings(market)

        when (mode) {
            "READ" -> Unit
            "BUY" -> order(Side.BUY, price)
            "SELL" -> order(Side.SELL, price)
            else -> error("알 수 없는 mode: $mode (READ/BUY/SELL)")
        }
    }

    private fun order(
        side: Side,
        price: BigDecimal,
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
        Thread.sleep(SETTLE_WAIT_MILLIS)
        logHoldings(properties.market)
    }

    private fun logHoldings(market: Market) {
        val holdings = broker.holdings(market)
        if (holdings.isEmpty()) {
            log.info { "[잔고] 보유 종목 없음 ($market)" }
            return
        }
        holdings.forEach { h ->
            val signal =
                if (h.averagePrice > BigDecimal.ZERO) exitRule.evaluate(h.averagePrice, h.currentPrice) else "N/A"
            log.info { "[잔고] ${h.market} ${h.symbol} ${h.quantity}주 평단=${h.averagePrice} 현재=${h.currentPrice} → 청산 규칙 판정: $signal" }
        }
    }

    private companion object {
        const val SETTLE_WAIT_MILLIS = 3000L
    }
}

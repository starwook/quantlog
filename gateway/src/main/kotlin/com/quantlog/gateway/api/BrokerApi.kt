package com.quantlog.gateway.api

import com.quantlog.gateway.broker.BrokerClient
import com.quantlog.gateway.broker.BuyingPower
import com.quantlog.gateway.broker.CallPriority
import com.quantlog.gateway.broker.CancelRequest
import com.quantlog.gateway.broker.Holding
import com.quantlog.gateway.broker.Market
import com.quantlog.gateway.broker.MinuteCandle
import com.quantlog.gateway.broker.OrderFillTotal
import com.quantlog.gateway.broker.OrderReceipt
import com.quantlog.gateway.broker.OrderRequest
import com.quantlog.gateway.broker.OrderStatus
import com.quantlog.gateway.broker.Quote
import com.quantlog.gateway.broker.Side
import com.quantlog.gateway.record.OrderService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** 주문 요청. [requestId] 는 앱이 요청마다 새로 만드는 ID — 같은 ID 가 다시 오면 또 보내지 않고 저장된 결과를 돌려준다. */
data class PlaceOrderBody(
    val requestId: String,
    val market: Market,
    val symbol: String,
    val side: Side,
    val quantity: Int,
    val limitPrice: BigDecimal,
)

data class CancelOrderBody(
    val requestId: String,
    val market: Market,
    val symbol: String,
    val orderNo: String,
    val branchNo: String,
    val quantity: Int,
    val limitPrice: BigDecimal,
)

/** [OrderStatus] 의 전송 형식. type = FILLED / OPEN / UNKNOWN, FILLED 일 때만 [price]. */
data class OrderStatusDto(val type: String, val price: BigDecimal? = null)

data class PriceDto(val price: BigDecimal?)

/**
 * 앱 → 게이트웨이 명령·조회(HTTP, JSON). 증권사 호출을 그대로 건네 줄 뿐 해석하지 않는다. 형식은 docs/contracts/gateway-http.md 가 기준이다.
 * 사용자가 방금 누른 주문처럼 즉발인 요청은 `X-Call-Priority: urgent` 헤더로 알리고, 게이트웨이는 호출 대기열에서 먼저 보낸다([CallPriority]).
 */
@RestController
@RequestMapping("/api")
class BrokerApi(
    private val broker: BrokerClient,
    private val orders: OrderService,
) {
    @PostMapping("/orders")
    fun place(
        @RequestBody body: PlaceOrderBody,
        @RequestHeader(name = PRIORITY_HEADER, required = false) priority: String?,
    ): OrderReceipt =
        withPriority(priority) {
            orders.place(body.requestId, OrderRequest(body.market, body.symbol, body.side, body.quantity, body.limitPrice))
        }

    @PostMapping("/orders/cancel")
    fun cancel(
        @RequestBody body: CancelOrderBody,
        @RequestHeader(name = PRIORITY_HEADER, required = false) priority: String?,
    ) = withPriority(priority) {
        orders.cancel(body.requestId, CancelRequest(body.market, body.symbol, body.orderNo, body.branchNo, body.quantity, body.limitPrice))
        mapOf("ok" to true)
    }

    @GetMapping("/orders/{orderNo}/status")
    fun status(
        @PathVariable orderNo: String,
        @RequestParam market: Market,
        @RequestParam quantity: Int,
        @RequestHeader(name = PRIORITY_HEADER, required = false) priority: String?,
    ): OrderStatusDto =
        withPriority(priority) {
            when (val s = broker.orderStatus(market, orderNo, quantity)) {
                is OrderStatus.Filled -> OrderStatusDto("FILLED", s.price)
                OrderStatus.Open -> OrderStatusDto("OPEN")
                OrderStatus.Unknown -> OrderStatusDto("UNKNOWN")
            }
        }

    @GetMapping("/orders/{orderNo}/filled-price")
    fun filledPrice(
        @PathVariable orderNo: String,
        @RequestParam market: Market,
        @RequestHeader(name = PRIORITY_HEADER, required = false) priority: String?,
    ): PriceDto = withPriority(priority) { PriceDto(broker.filledPrice(market, orderNo)) }

    @GetMapping("/orders/fills/today")
    fun todayFills(
        @RequestParam market: Market,
        @RequestHeader(name = PRIORITY_HEADER, required = false) priority: String?,
    ): List<OrderFillTotal> = withPriority(priority) { broker.todayOrderFills(market) }

    @GetMapping("/quotes/{market}/{symbol}")
    fun quote(
        @PathVariable market: Market,
        @PathVariable symbol: String,
        @RequestHeader(name = PRIORITY_HEADER, required = false) priority: String?,
    ): Quote = withPriority(priority) { broker.quote(market, symbol) }

    @GetMapping("/quotes/{market}/{symbol}/previous-close")
    fun previousClose(
        @PathVariable market: Market,
        @PathVariable symbol: String,
    ): PriceDto = PriceDto(broker.previousClose(market, symbol))

    @GetMapping("/quotes/{market}/{symbol}/candles")
    fun candles(
        @PathVariable market: Market,
        @PathVariable symbol: String,
        @RequestParam time: String,
    ): List<MinuteCandle> = broker.minuteCandles(market, symbol, LocalTime.parse(time, TIME_FORMAT))

    @GetMapping("/account/holdings")
    fun holdings(
        @RequestParam market: Market,
        @RequestHeader(name = PRIORITY_HEADER, required = false) priority: String?,
    ): List<Holding> = withPriority(priority) { broker.holdings(market) }

    @GetMapping("/account/buying-power")
    fun buyingPower(
        @RequestParam market: Market,
        @RequestParam symbol: String,
        @RequestParam price: BigDecimal,
    ): BuyingPower = broker.buyingPower(market, symbol, price)

    private fun <T> withPriority(
        priority: String?,
        block: () -> T,
    ): T = if (priority.equals(URGENT, ignoreCase = true)) CallPriority.urgent { block() } else block()

    companion object {
        const val PRIORITY_HEADER = "X-Call-Priority"
        const val URGENT = "urgent"
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HHmmss")
    }
}

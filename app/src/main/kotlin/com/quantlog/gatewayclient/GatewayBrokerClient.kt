package com.quantlog.gatewayclient

import com.fasterxml.jackson.databind.ObjectMapper
import com.quantlog.broker.BrokerClient
import com.quantlog.broker.BuyingPower
import com.quantlog.broker.CallPriority
import com.quantlog.broker.CancelRequest
import com.quantlog.broker.Holding
import com.quantlog.broker.Market
import com.quantlog.broker.MinuteCandle
import com.quantlog.broker.OrderFillTotal
import com.quantlog.broker.OrderReceipt
import com.quantlog.broker.OrderRequest
import com.quantlog.broker.OrderStatus
import com.quantlog.broker.Quote
import mu.KotlinLogging
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Primary
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import java.math.BigDecimal
import java.net.http.HttpClient
import java.time.Duration
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID

private val log = KotlinLogging.logger {}

@ConfigurationProperties(prefix = "quantlog.gateway")
data class GatewayClientProperties(
    val baseUrl: String = "http://localhost:8081",
    /** 게이트웨이와 맞춘 공유 토큰(`X-Gateway-Token`). 비우면 보내지 않는다. */
    val token: String = "",
    val connectTimeoutMillis: Long = 2_000,
    /** 증권사 호출은 호출 간격 대기열 때문에 몇 초 걸릴 수 있다(주문은 더). */
    val readTimeoutMillis: Long = 30_000,
    /** false 면 게이트웨이와 연결하지 않는다(테스트·점검용). */
    val enabled: Boolean = true,
)

/** 게이트웨이가 증권사나 요청 때문에 실패를 돌려줬다. [status] 는 HTTP 상태, [code] 는 증권사 msg_cd 또는 게이트웨이 코드. */
class GatewayException(
    message: String,
    val status: Int,
    val code: String?,
) : RuntimeException(message)

/** 게이트웨이에 닿지 못했다(연결 거부·시간 초과). 주문은 보내졌는지 모를 수 있다 — 호출한 쪽이 주문 상태를 조회해 확정해야 한다. */
class GatewayUnavailableException(message: String, cause: Throwable?) : RuntimeException(message, cause)

/**
 * 증권사 호출을 게이트웨이(HTTP)로 보내는 [BrokerClient]. 앱은 증권사에 직접 닿지 않는다 — 키도 없다. 형식은 docs/contracts/gateway-http.md.
 * 지금 스레드가 즉발 등급이면([CallPriority]) `X-Call-Priority: urgent` 헤더로 알려 게이트웨이의 호출 대기열에서 먼저 나가게 한다.
 * 주문·취소는 요청마다 새 요청 ID 를 붙인다 — 게이트웨이가 같은 ID 의 중복 요청을 막는다.
 */
@Primary
@Component
class GatewayBrokerClient(
    private val properties: GatewayClientProperties,
    private val objectMapper: ObjectMapper,
) : BrokerClient {
    private val client: RestClient =
        RestClient.builder()
            .baseUrl(properties.baseUrl)
            .requestFactory(
                JdkClientHttpRequestFactory(
                    HttpClient.newBuilder().connectTimeout(Duration.ofMillis(properties.connectTimeoutMillis)).build(),
                ).also { it.setReadTimeout(Duration.ofMillis(properties.readTimeoutMillis)) },
            )
            .build()

    override fun quote(
        market: Market,
        symbol: String,
    ): Quote = get("/api/quotes/${market.name}/$symbol", Quote::class.java)

    override fun previousClose(
        market: Market,
        symbol: String,
    ): BigDecimal? = get("/api/quotes/${market.name}/$symbol/previous-close", PriceBody::class.java).price

    override fun minuteCandles(
        market: Market,
        symbol: String,
        atTime: LocalTime,
    ): List<MinuteCandle> =
        getList(
            "/api/quotes/${market.name}/$symbol/candles?time=${atTime.format(TIME)}",
            Array<MinuteCandle>::class.java,
        )

    override fun filledPrice(
        market: Market,
        orderNo: String,
    ): BigDecimal? = get("/api/orders/$orderNo/filled-price?market=${market.name}", PriceBody::class.java).price

    override fun orderStatus(
        market: Market,
        orderNo: String,
        quantity: Int,
    ): OrderStatus {
        val dto = get("/api/orders/$orderNo/status?market=${market.name}&quantity=$quantity", OrderStatusBody::class.java)
        return when (dto.type) {
            "FILLED" -> OrderStatus.Filled(requireNotNull(dto.price) { "게이트웨이가 FILLED 인데 체결가를 안 줬다" })
            "OPEN" -> OrderStatus.Open
            else -> OrderStatus.Unknown
        }
    }

    override fun todayOrderFills(market: Market): List<OrderFillTotal> =
        getList("/api/orders/fills/today?market=${market.name}", Array<OrderFillTotal>::class.java)

    override fun buyingPower(
        market: Market,
        symbol: String,
        price: BigDecimal,
    ): BuyingPower =
        get(
            "/api/account/buying-power?market=${market.name}&symbol=$symbol&price=${price.toPlainString()}",
            BuyingPower::class.java,
        )

    override fun holdings(market: Market): List<Holding> =
        getList(
            "/api/account/holdings?market=${market.name}",
            Array<Holding>::class.java,
        )

    override fun placeOrder(order: OrderRequest): OrderReceipt =
        post(
            "/api/orders",
            mapOf(
                "requestId" to UUID.randomUUID().toString(),
                "market" to order.market.name,
                "symbol" to order.symbol,
                "side" to order.side.name,
                "quantity" to order.quantity,
                "limitPrice" to order.limitPrice,
            ),
            OrderReceipt::class.java,
        )

    override fun cancelOrder(request: CancelRequest) {
        post(
            "/api/orders/cancel",
            mapOf(
                "requestId" to UUID.randomUUID().toString(),
                "market" to request.market.name,
                "symbol" to request.symbol,
                "orderNo" to request.orderNo,
                "branchNo" to request.branchNo,
                "quantity" to request.quantity,
                "limitPrice" to request.limitPrice,
            ),
            Map::class.java,
        )
    }

    private fun <T : Any> get(
        uri: String,
        type: Class<T>,
    ): T =
        call {
            client.get().uri(uri).headers(::addHeaders).retrieve().body(type)
        } ?: throw GatewayException("게이트웨이 응답이 비어 있다: $uri", 200, null)

    private fun <T> getList(
        uri: String,
        arrayType: Class<Array<T>>,
    ): List<T> =
        (call { client.get().uri(uri).headers(::addHeaders).retrieve().body(arrayType) } ?: emptyArray<Any?>() as Array<T>).toList()

    private fun <T : Any> post(
        uri: String,
        body: Any,
        type: Class<T>,
    ): T =
        call {
            client.post().uri(uri).headers(::addHeaders).body(body).retrieve().body(type)
        } ?: throw GatewayException("게이트웨이 응답이 비어 있다: $uri", 200, null)

    private fun addHeaders(headers: org.springframework.http.HttpHeaders) {
        if (properties.token.isNotBlank()) headers.set(TOKEN_HEADER, properties.token)
        if (CallPriority.isUrgent()) headers.set(PRIORITY_HEADER, "urgent")
    }

    private fun <T> call(block: () -> T): T =
        try {
            block()
        } catch (e: RestClientResponseException) {
            val body = runCatching { objectMapper.readTree(e.responseBodyAsString) }.getOrNull()
            val message = body?.path("error")?.asText()?.takeIf { it.isNotBlank() } ?: "게이트웨이 오류 HTTP ${e.statusCode.value()}"
            val code = body?.path("code")?.asText()?.takeIf { it.isNotBlank() }
            throw GatewayException(message, e.statusCode.value(), code)
        } catch (e: ResourceAccessException) {
            log.debug { "게이트웨이에 닿지 못했다: ${e.message}" }
            throw GatewayUnavailableException("게이트웨이에 닿지 못했다: ${e.message}", e)
        }

    data class PriceBody(val price: BigDecimal? = null)

    data class OrderStatusBody(val type: String = "UNKNOWN", val price: BigDecimal? = null)

    private companion object {
        const val TOKEN_HEADER = "X-Gateway-Token"
        const val PRIORITY_HEADER = "X-Call-Priority"
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HHmmss")
    }
}

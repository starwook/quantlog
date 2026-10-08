package com.quantlog.gateway.kis

import com.fasterxml.jackson.databind.JsonNode
import com.quantlog.gateway.broker.BrokerClient
import com.quantlog.gateway.broker.BuyingPower
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
import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

private val log = KotlinLogging.logger {}

/**
 * 한국투자증권 모의투자 구현체(국내 주식 전용). 스펙 출처: docs/kis-api/examples (TR ID 는 모의투자용 V 접두).
 * 응답 필드명은 예제 코드에서 확인되지 않은 부분이 있어 첫 실행 시 KIS_MOCK_LOG_RAW=true 로 원문을 확인한다.
 */
@Component
class KisMockBroker(
    private val api: KisApiClient,
    private val properties: KisProperties,
    private val realtimeClient: KisRealtimeClient,
) : BrokerClient {
    /** 실시간 시세는 호가 단위(aspr_unit)를 안 주므로, REST 로 마지막에 받은 값을 잠깐 재사용한다. */
    private val tickSizeCache = ConcurrentHashMap<String, BigDecimal>()

    /** 전일 종가는 하루 동안 안 바뀌므로 (시장, 종목, 날짜)별로 한 번만 REST 로 받는다. */
    private val previousCloseCache = ConcurrentHashMap<String, BigDecimal>()

    override fun previousClose(
        market: Market,
        symbol: String,
    ): BigDecimal? {
        val key = "$market:$symbol:${LocalDate.now(market.zone)}"
        previousCloseCache[key]?.let { return it }
        val output =
            api.get(
                "/uapi/domestic-stock/v1/quotations/inquire-price",
                "FHKST01010100",
                mapOf("FID_COND_MRKT_DIV_CODE" to "J", "FID_INPUT_ISCD" to symbol),
            ).path("output")
        // 전일 종가(stck_prdy_clpr), 없으면 기준가(stck_sdpr).
        val fields = listOf("stck_prdy_clpr", "stck_sdpr")
        val value = fields.map { output.decimal(it) }.firstOrNull { it > BigDecimal.ZERO }
        if (value == null) log.warn { "[전일 종가] $market $symbol 응답에서 $fields 를 못 찾음. 응답 필드: ${output.fieldNames().asSequence().toList()}" }
        return value?.also { previousCloseCache[key] = it }
    }

    override fun quote(
        market: Market,
        symbol: String,
    ): Quote {
        val cachedTick = tickSizeCache[symbol]
        val live = realtimeClient.latestPrice(symbol)
        if (cachedTick != null && live != null && Duration.between(live.at, Instant.now()) <= LIVE_QUOTE_FRESHNESS) {
            return Quote(live.price, cachedTick)
        }
        return quoteViaRest(symbol)
    }

    private fun quoteViaRest(symbol: String): Quote {
        val res =
            api.get(
                "/uapi/domestic-stock/v1/quotations/inquire-price",
                "FHKST01010100",
                mapOf("FID_COND_MRKT_DIV_CODE" to "J", "FID_INPUT_ISCD" to symbol),
            )
        val output = res.path("output")
        // aspr_unit: 현재가 기준 호가 단위 (2026-09-29 모의투자 실측: 삼성전자 273,000원 → 500)
        val tickSize = output.decimal("aspr_unit")
        require(tickSize > BigDecimal.ZERO) { "KIS 시세에 호가 단위(aspr_unit)가 없음: $symbol" }
        tickSizeCache[symbol] = tickSize
        return Quote(output.decimal("stck_prpr"), tickSize)
    }

    override fun buyingPower(
        market: Market,
        symbol: String,
        price: BigDecimal,
    ): BuyingPower {
        val res =
            api.get(
                "/uapi/domestic-stock/v1/trading/inquire-psbl-order",
                "VTTC8908R",
                accountParams() +
                    mapOf(
                        "PDNO" to symbol,
                        "ORD_UNPR" to price.priceText(),
                        "ORD_DVSN" to "00",
                        "CMA_EVLU_AMT_ICLD_YN" to "N",
                        "OVRS_ICLD_YN" to "N",
                    ),
            )
        val out = res.path("output")
        return BuyingPower(market.currency, out.decimal("ord_psbl_cash"), out.decimal("max_buy_qty"))
    }

    override fun holdings(market: Market): List<Holding> {
        val res =
            api.get(
                "/uapi/domestic-stock/v1/trading/inquire-balance",
                "VTTC8434R",
                accountParams() +
                    mapOf(
                        "AFHR_FLPR_YN" to "N",
                        "OFL_YN" to "",
                        "INQR_DVSN" to "02",
                        "UNPR_DVSN" to "01",
                        "FUND_STTL_ICLD_YN" to "N",
                        "FNCG_AMT_AUTO_RDPT_YN" to "N",
                        "PRCS_DVSN" to "00",
                        "CTX_AREA_FK100" to "",
                        "CTX_AREA_NK100" to "",
                    ),
            )
        return res.path("output1").mapNotNull { row ->
            Holding(
                market = Market.KR,
                symbol = row.path("pdno").asText(),
                name = row.path("prdt_name").asText(),
                quantity = row.decimal("hldg_qty"),
                averagePrice = row.decimal("pchs_avg_pric"),
                currentPrice = row.decimal("prpr"),
            ).takeIf { it.quantity > BigDecimal.ZERO }
        }
    }

    override fun placeOrder(order: OrderRequest): OrderReceipt {
        val trId = if (order.side == Side.BUY) "VTTC0012U" else "VTTC0011U"
        val res =
            api.post(
                "/uapi/domestic-stock/v1/trading/order-cash",
                trId,
                accountParams() +
                    mapOf(
                        "PDNO" to order.symbol,
                        "ORD_DVSN" to "00",
                        "ORD_QTY" to order.quantity.toString(),
                        "ORD_UNPR" to order.limitPrice.priceText(),
                        "EXCG_ID_DVSN_CD" to "KRX",
                        "SLL_TYPE" to if (order.side == Side.SELL) "01" else "",
                        "CNDT_PRIC" to "",
                    ),
            )
        val out = res.path("output")
        return OrderReceipt(
            orderNo = out.path("ODNO").asText(),
            message = res.path("msg1").asText(),
            branchNo = out.path("KRX_FWDG_ORD_ORGNO").asText(),
        )
    }

    /** 정정취소 API(docs/kis-api 의 order_rvsecncl 예제)의 취소(02). 모의 TR: VTTC0013U. */
    override fun cancelOrder(request: CancelRequest) {
        api.post(
            "/uapi/domestic-stock/v1/trading/order-rvsecncl",
            "VTTC0013U",
            accountParams() +
                mapOf(
                    "KRX_FWDG_ORD_ORGNO" to request.branchNo,
                    "ORGN_ODNO" to request.orderNo,
                    "ORD_DVSN" to "00",
                    "RVSE_CNCL_DVSN_CD" to "02",
                    "ORD_QTY" to request.quantity.toString(),
                    "ORD_UNPR" to "0",
                    "QTY_ALL_ORD_YN" to "Y",
                    "EXCG_ID_DVSN_CD" to "KRX",
                ),
        )
    }

    /** 실측 확인(2026-09-29, 삼성전자): output2 필드명이 아래와 정확히 일치. docs/kis-api/README.md 참고. */
    override fun minuteCandles(
        market: Market,
        symbol: String,
        atTime: LocalTime,
    ): List<MinuteCandle> {
        val res =
            api.get(
                "/uapi/domestic-stock/v1/quotations/inquire-time-itemchartprice",
                "FHKST03010200",
                mapOf(
                    "FID_COND_MRKT_DIV_CODE" to "J",
                    "FID_INPUT_ISCD" to symbol,
                    "FID_INPUT_HOUR_1" to atTime.format(HOUR_FORMAT),
                    "FID_PW_DATA_INCU_YN" to "Y",
                    "FID_ETC_CLS_CODE" to "",
                ),
            )
        return res.path("output2").map { node ->
            MinuteCandle(
                date = LocalDate.parse(node.path("stck_bsop_date").asText(), DATE_FORMAT),
                time = LocalTime.parse(node.path("stck_cntg_hour").asText(), HOUR_FORMAT),
                open = node.decimal("stck_oprc"),
                high = node.decimal("stck_hgpr"),
                low = node.decimal("stck_lwpr"),
                close = node.decimal("stck_prpr"),
                volume = node.path("cntg_vol").asText().toLong(),
            )
        }
    }

    override fun filledPrice(
        market: Market,
        orderNo: String,
    ): BigDecimal? = (orderStatus(market, orderNo, 1) as? OrderStatus.Filled)?.price

    /**
     * 주식일별주문체결조회를 **전체(00)**로 주문번호만 걸어 부른다 — 체결분(01)만 부르면 "미체결"과 "아직 조회에 안 잡힘"이
     * 똑같이 빈 응답이라 구분이 안 된다. 행이 있고 체결 수량이 주문 수량 미만이면 [OrderStatus.Open], 행이 없으면 [OrderStatus.Unknown].
     * 실측 확인(2026-09-29): output1 필드명(odno, tot_ccld_qty, avg_prvs)이 일치. docs/kis-api/README.md 참고.
     */
    override fun orderStatus(
        market: Market,
        orderNo: String,
        quantity: Int,
    ): OrderStatus {
        val res = dailyCcld(orderNo)
        val row = res.path("output1").firstOrNull { it.path("odno").asText() == orderNo } ?: return OrderStatus.Unknown
        val filledQty = row.path("tot_ccld_qty").asText("0").toIntOrNull() ?: 0
        return if (filledQty >= quantity) OrderStatus.Filled(row.decimal("avg_prvs")) else OrderStatus.Open
    }

    /** 오늘 주문 체결 전체(한 페이지). [orderNo] 를 주면 그 주문만, 비우면 오늘 주문 전부. */
    private fun dailyCcld(orderNo: String): JsonNode {
        val today = LocalDate.now(KST).format(DATE_FORMAT)
        return api.get(
            "/uapi/domestic-stock/v1/trading/inquire-daily-ccld",
            "VTTC0081R",
            accountParams() +
                mapOf(
                    "INQR_STRT_DT" to today,
                    "INQR_END_DT" to today,
                    "SLL_BUY_DVSN_CD" to "00",
                    "PDNO" to "",
                    "CCLD_DVSN" to "00",
                    "INQR_DVSN" to "00",
                    "INQR_DVSN_3" to "00",
                    "ORD_GNO_BRNO" to "",
                    "ODNO" to orderNo,
                    "INQR_DVSN_1" to "",
                    "CTX_AREA_FK100" to "",
                    "CTX_AREA_NK100" to "",
                    "EXCG_ID_DVSN_CD" to "KRX",
                ),
        )
    }

    /** 필드명(odno, tot_ccld_qty, avg_prvs)은 2026-09-29 실측으로 확인됐다. 체결이 0인 주문은 뺀다. */
    override fun todayOrderFills(market: Market): List<OrderFillTotal> =
        dailyCcld("").path("output1").mapNotNull { row ->
            val quantity = row.path("tot_ccld_qty").asText("0").toIntOrNull() ?: 0
            if (quantity <= 0) return@mapNotNull null
            OrderFillTotal(row.path("odno").asText(), quantity, row.decimal("avg_prvs"))
        }

    private fun accountParams() = mapOf("CANO" to properties.accountNumber, "ACNT_PRDT_CD" to properties.accountProductCode)

    /** 원화는 정수. */
    private fun BigDecimal.priceText(): String = setScale(0, RoundingMode.HALF_UP).toPlainString()

    private fun JsonNode.decimal(field: String): BigDecimal {
        val text = path(field).asText("").trim()
        return if (text.isEmpty()) BigDecimal.ZERO else BigDecimal(text)
    }

    private companion object {
        val KST = java.time.ZoneId.of("Asia/Seoul")
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
        val HOUR_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HHmmss")
        val LIVE_QUOTE_FRESHNESS: Duration = Duration.ofSeconds(10)
    }
}

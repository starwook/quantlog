package com.quantlog.broker

import com.fasterxml.jackson.databind.JsonNode
import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val log = KotlinLogging.logger {}

/**
 * 한국투자증권(모의투자) [BrokerClient]. **한투 문서대로 요청을 만들고 응답을 읽는다** — 게이트웨이는 요청·응답을 그대로 전하기만 하므로
 * 한투 필드 이름·응답 형식이 바뀌면 여기만 고치면 된다. 스펙 출처: docs/kis-api/examples (TR ID 는 모의투자용 V 접두).
 * 호출 우선순위는 [CallPriority] 로 게이트웨이에 알려진다. 계좌번호는 게이트웨이가 끼워 넣는다.
 */
@Component
class KisBrokerClient(
    private val rest: KisRest,
    private val latestPrices: LatestPrices,
    private val clock: Clock = Clock.systemUTC(),
) : BrokerClient {
    /** 실시간 시세는 호가 단위(aspr_unit)를 안 주므로, REST 로 마지막에 받은 값을 잠깐 재사용한다. */
    private val tickSizeCache = ConcurrentHashMap<String, BigDecimal>()

    /** 전일 종가는 하루 동안 안 바뀌므로 (시장, 종목, 날짜)별로 한 번만 REST 로 받는다. */
    private val previousCloseCache = ConcurrentHashMap<String, BigDecimal>()

    override fun previousClose(
        market: Market,
        symbol: String,
    ): BigDecimal? {
        val key = "$market:$symbol:${LocalDate.now(clock.withZone(market.zone))}"
        previousCloseCache[key]?.let { return it }
        val output = inquirePrice(symbol).path("output")
        // 전일 종가(stck_prdy_clpr), 없으면 기준가(stck_sdpr).
        val fields = listOf("stck_prdy_clpr", "stck_sdpr")
        val value = fields.map { output.decimal(it) }.firstOrNull { it > BigDecimal.ZERO }
        if (value == null) log.warn { "[전일 종가] $market $symbol 응답에서 $fields 를 못 찾음. 응답 필드: ${output.fieldNames().asSequence().toList()}" }
        return value?.also { previousCloseCache[key] = it }
    }

    /** 실시간 최신가가 [LIVE_QUOTE_FRESHNESS] 안이고 호가 단위를 알면 그 값을, 아니면 REST(FHKST01010100)로 받는다. */
    override fun quote(
        market: Market,
        symbol: String,
    ): Quote {
        val cachedTick = tickSizeCache[symbol]
        val live = latestPrices.get(symbol)
        if (cachedTick != null && live != null && Duration.between(live.at, clock.instant()) <= LIVE_QUOTE_FRESHNESS) {
            return Quote(live.price, cachedTick)
        }
        val output = inquirePrice(symbol).path("output")
        // aspr_unit: 현재가 기준 호가 단위 (2026-09-29 모의투자 실측: 삼성전자 273,000원 → 500)
        val tickSize = output.decimal("aspr_unit")
        require(tickSize > BigDecimal.ZERO) { "KIS 시세에 호가 단위(aspr_unit)가 없음: $symbol" }
        tickSizeCache[symbol] = tickSize
        return Quote(output.decimal("stck_prpr"), tickSize)
    }

    private fun inquirePrice(symbol: String): JsonNode =
        call(
            "/uapi/domestic-stock/v1/quotations/inquire-price",
            "FHKST01010100",
            rest.get(
                "/uapi/domestic-stock/v1/quotations/inquire-price",
                "FHKST01010100",
                mapOf("FID_COND_MRKT_DIV_CODE" to "J", "FID_INPUT_ISCD" to symbol),
            ),
        )

    override fun buyingPower(
        market: Market,
        symbol: String,
        price: BigDecimal,
    ): BuyingPower {
        val path = "/uapi/domestic-stock/v1/trading/inquire-psbl-order"
        val res =
            call(
                path,
                "VTTC8908R",
                rest.get(
                    path,
                    "VTTC8908R",
                    mapOf(
                        "PDNO" to symbol,
                        "ORD_UNPR" to price.priceText(),
                        "ORD_DVSN" to "00",
                        "CMA_EVLU_AMT_ICLD_YN" to "N",
                        "OVRS_ICLD_YN" to "N",
                    ),
                ),
            )
        val out = res.path("output")
        return BuyingPower(market.currency, out.decimal("ord_psbl_cash"), out.decimal("max_buy_qty"))
    }

    override fun holdings(market: Market): List<Holding> = KisBalanceParser.parse(balance())

    /** 주식잔고조회(VTTC8434R) 응답 원문. 계좌번호는 게이트웨이가 끼운다. */
    private fun balance(): JsonNode {
        val path = "/uapi/domestic-stock/v1/trading/inquire-balance"
        return call(path, "VTTC8434R", rest.get(path, "VTTC8434R", KisBalanceParser.REQUEST_PARAMS))
    }

    override fun placeOrder(order: OrderRequest): OrderReceipt {
        val path = "/uapi/domestic-stock/v1/trading/order-cash"
        val trId = if (order.side == Side.BUY) "VTTC0012U" else "VTTC0011U"
        val res =
            call(
                path,
                trId,
                rest.post(
                    path,
                    trId,
                    mapOf(
                        "PDNO" to order.symbol,
                        "ORD_DVSN" to "00",
                        "ORD_QTY" to order.quantity.toString(),
                        "ORD_UNPR" to order.limitPrice.priceText(),
                        "EXCG_ID_DVSN_CD" to "KRX",
                        "SLL_TYPE" to if (order.side == Side.SELL) "01" else "",
                        "CNDT_PRIC" to "",
                    ),
                    requestId = UUID.randomUUID().toString(),
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
        val path = "/uapi/domestic-stock/v1/trading/order-rvsecncl"
        call(
            path,
            "VTTC0013U",
            rest.post(
                path,
                "VTTC0013U",
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
                requestId = UUID.randomUUID().toString(),
            ),
        )
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
        val rows = dailyCcld(orderNo, maxPages = 1)
        val row = rows.firstOrNull { it.path("odno").asText() == orderNo } ?: return OrderStatus.Unknown
        val filledQty = row.path("tot_ccld_qty").asText("0").toIntOrNull() ?: 0
        return if (filledQty >= quantity) OrderStatus.Filled(row.decimal("avg_prvs")) else OrderStatus.Open
    }

    /** 필드명(odno, tot_ccld_qty, avg_prvs)은 2026-09-29 실측으로 확인됐다. 체결이 0인 주문은 뺀다. 여러 쪽이면 이어서 받는다. */
    override fun todayOrderFills(market: Market): List<OrderFillTotal> =
        dailyCcld("", maxPages = MAX_PAGES).mapNotNull { row ->
            val quantity = row.path("tot_ccld_qty").asText("0").toIntOrNull() ?: 0
            if (quantity <= 0) return@mapNotNull null
            OrderFillTotal(row.path("odno").asText(), quantity, row.decimal("avg_prvs"))
        }

    /**
     * 오늘 주문 체결 전체의 `output1` 행. [orderNo] 를 주면 그 주문만, 비우면 오늘 주문 전부.
     * 응답 헤더 `tr_cont` 가 M·F 면 다음 쪽이 있다 — 응답의 `ctx_area_fk100`·`ctx_area_nk100` 을 요청의 `CTX_AREA_*` 에 넣고 헤더 `tr_cont: N` 으로 이어 받는다(최대 [maxPages] 쪽).
     */
    private fun dailyCcld(
        orderNo: String,
        maxPages: Int,
    ): List<JsonNode> {
        val path = "/uapi/domestic-stock/v1/trading/inquire-daily-ccld"
        val today = LocalDate.now(clock.withZone(Market.KR.zone)).format(DATE_FORMAT)
        val rows = mutableListOf<JsonNode>()
        var fk = ""
        var nk = ""
        var trCont = ""
        repeat(maxPages) {
            val reply =
                rest.get(
                    path,
                    "VTTC0081R",
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
                        "CTX_AREA_FK100" to fk,
                        "CTX_AREA_NK100" to nk,
                        "EXCG_ID_DVSN_CD" to "KRX",
                    ),
                    trCont,
                )
            val res = call(path, "VTTC0081R", reply)
            rows += res.path("output1").toList()
            if (reply.trCont != "M" && reply.trCont != "F") return rows
            fk = res.path("ctx_area_fk100").asText("").trim()
            nk = res.path("ctx_area_nk100").asText("").trim()
            trCont = "N"
        }
        return rows
    }

    /** 한투 응답의 `rt_cd` 가 0 이 아니면 [KisApiException]. 맞으면 응답 본문. */
    private fun call(
        path: String,
        trId: String,
        reply: KisReply,
    ): JsonNode {
        if (reply.body.path("rt_cd").asText() != "0") {
            val code = reply.body.path("msg_cd").asText()
            throw KisApiException("KIS 오류 $path ($trId): [$code] ${reply.body.path("msg1").asText()}", code = code)
        }
        return reply.body
    }

    /** 원화는 정수. */
    private fun BigDecimal.priceText(): String = setScale(0, RoundingMode.HALF_UP).toPlainString()

    private companion object {
        const val MAX_PAGES = 10
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
        val LIVE_QUOTE_FRESHNESS: Duration = Duration.ofSeconds(10)
    }
}

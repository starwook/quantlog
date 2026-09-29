package com.quantlog.broker.kis

import com.fasterxml.jackson.databind.JsonNode
import com.quantlog.broker.BrokerClient
import com.quantlog.broker.BuyingPower
import com.quantlog.broker.Holding
import com.quantlog.broker.Market
import com.quantlog.broker.MinuteCandle
import com.quantlog.broker.OrderReceipt
import com.quantlog.broker.OrderRequest
import com.quantlog.broker.Quote
import com.quantlog.broker.Side
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * 한국투자증권 모의투자 구현체. 스펙 출처: docs/kis-api/examples (TR ID 는 모의투자용 V 접두).
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

    override fun quote(
        market: Market,
        symbol: String,
    ): Quote {
        if (!market.isOverseas) {
            val cachedTick = tickSizeCache[symbol]
            val live = realtimeClient.latestPrice(symbol)
            if (cachedTick != null && live != null && Duration.between(live.at, Instant.now()) <= LIVE_QUOTE_FRESHNESS) {
                return Quote(live.price, cachedTick)
            }
        }
        return quoteViaRest(market, symbol)
    }

    private fun quoteViaRest(
        market: Market,
        symbol: String,
    ): Quote =
        if (market.isOverseas) {
            val res =
                api.get(
                    "/uapi/overseas-price/v1/quotations/price",
                    "HHDFS00000300",
                    mapOf("AUTH" to "", "EXCD" to market.quoteExchangeCode(), "SYMB" to symbol),
                )
            val price = res.path("output").decimal("last")
            Quote(price, market.overseasTickSize(price))
        } else {
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
            Quote(output.decimal("stck_prpr"), tickSize)
        }

    override fun buyingPower(
        market: Market,
        symbol: String,
        price: BigDecimal,
    ): BuyingPower =
        if (market.isOverseas) {
            val res =
                api.get(
                    "/uapi/overseas-stock/v1/trading/inquire-psamount",
                    "VTTS3007R",
                    accountParams() +
                        mapOf(
                            "OVRS_EXCG_CD" to market.orderExchangeCode(),
                            "OVRS_ORD_UNPR" to price.priceText(market),
                            "ITEM_CD" to symbol,
                        ),
                )
            val out = res.path("output")
            BuyingPower(market.currency, out.decimal("ord_psbl_frcr_amt"), out.decimal("max_ord_psbl_qty"))
        } else {
            val res =
                api.get(
                    "/uapi/domestic-stock/v1/trading/inquire-psbl-order",
                    "VTTC8908R",
                    accountParams() +
                        mapOf(
                            "PDNO" to symbol,
                            "ORD_UNPR" to price.priceText(market),
                            "ORD_DVSN" to "00",
                            "CMA_EVLU_AMT_ICLD_YN" to "N",
                            "OVRS_ICLD_YN" to "N",
                        ),
                )
            val out = res.path("output")
            BuyingPower(market.currency, out.decimal("ord_psbl_cash"), out.decimal("max_buy_qty"))
        }

    override fun holdings(market: Market): List<Holding> =
        if (market.isOverseas) {
            val res =
                api.get(
                    "/uapi/overseas-stock/v1/trading/inquire-balance",
                    "VTTS3012R",
                    accountParams() +
                        mapOf(
                            "OVRS_EXCG_CD" to "NASD",
                            "TR_CRCY_CD" to "USD",
                            "CTX_AREA_FK200" to "",
                            "CTX_AREA_NK200" to "",
                        ),
                )
            res.path("output1").mapNotNull { row ->
                Holding(
                    market = row.path("ovrs_excg_cd").asText().toUsMarket(),
                    symbol = row.path("ovrs_pdno").asText(),
                    name = row.path("ovrs_item_name").asText(),
                    quantity = row.decimal("ovrs_cblc_qty"),
                    averagePrice = row.decimal("pchs_avg_pric"),
                    currentPrice = row.decimal("now_pric2"),
                ).takeIf { it.quantity > BigDecimal.ZERO }
            }
        } else {
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
            res.path("output1").mapNotNull { row ->
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
        val market = order.market
        val res =
            if (market.isOverseas) {
                val trId = if (order.side == Side.BUY) "VTTT1002U" else "VTTT1001U"
                api.post(
                    "/uapi/overseas-stock/v1/trading/order",
                    trId,
                    accountParams() +
                        mapOf(
                            "OVRS_EXCG_CD" to market.orderExchangeCode(),
                            "PDNO" to order.symbol,
                            "ORD_QTY" to order.quantity.toString(),
                            "OVRS_ORD_UNPR" to order.limitPrice.priceText(market),
                            "CTAC_TLNO" to "",
                            "MGCO_APTM_ODNO" to "",
                            "SLL_TYPE" to if (order.side == Side.SELL) "00" else "",
                            "ORD_SVR_DVSN_CD" to "0",
                            // 모의투자는 지정가만 가능
                            "ORD_DVSN" to "00",
                        ),
                )
            } else {
                val trId = if (order.side == Side.BUY) "VTTC0012U" else "VTTC0011U"
                api.post(
                    "/uapi/domestic-stock/v1/trading/order-cash",
                    trId,
                    accountParams() +
                        mapOf(
                            "PDNO" to order.symbol,
                            "ORD_DVSN" to "00",
                            "ORD_QTY" to order.quantity.toString(),
                            "ORD_UNPR" to order.limitPrice.priceText(market),
                            "EXCG_ID_DVSN_CD" to "KRX",
                            "SLL_TYPE" to if (order.side == Side.SELL) "01" else "",
                            "CNDT_PRIC" to "",
                        ),
                )
            }
        val out = res.path("output")
        return OrderReceipt(orderNo = out.path("ODNO").asText(), message = res.path("msg1").asText())
    }

    /** 실측 확인(2026-09-29, 삼성전자): output2 필드명이 아래와 정확히 일치. docs/kis-api/README.md 참고. */
    override fun minuteCandles(
        market: Market,
        symbol: String,
        atTime: LocalTime,
    ): List<MinuteCandle> =
        if (market.isOverseas) {
            overseasMinuteCandles(market, symbol)
        } else {
            domesticMinuteCandles(symbol, atTime)
        }

    private fun domesticMinuteCandles(
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

    /**
     * 실측 확인(2026-09-29, SOXL 프리마켓): 공식 예제엔 output2 필드명이 안 적혀 있어 KIS 일반 명명
     * 규칙으로 추정(tymd/xhms/open/high/low/last/evol)했는데, SmokeTestRunner CANDLES 모드로 받아보니
     * 실제 가격·거래량이 정상 범위로 나와 필드명이 맞는 것으로 확인됨.
     */
    private fun overseasMinuteCandles(
        market: Market,
        symbol: String,
    ): List<MinuteCandle> {
        val res =
            api.get(
                "/uapi/overseas-price/v1/quotations/inquire-time-itemchartprice",
                "HHDFS76950200",
                mapOf(
                    "AUTH" to "",
                    "EXCD" to market.quoteExchangeCode(),
                    "SYMB" to symbol,
                    "NMIN" to "1",
                    "PINC" to "0",
                    "NEXT" to "",
                    "NREC" to "120",
                    "FILL" to "",
                    "KEYB" to "",
                ),
            )
        return res.path("output2").mapNotNull { node ->
            val dateText = node.path("tymd").asText("")
            val timeText = node.path("xhms").asText("")
            if (dateText.isEmpty() || timeText.isEmpty()) return@mapNotNull null
            MinuteCandle(
                date = LocalDate.parse(dateText, DATE_FORMAT),
                time = LocalTime.parse(timeText, HOUR_FORMAT),
                open = node.decimal("open"),
                high = node.decimal("high"),
                low = node.decimal("low"),
                close = node.decimal("last"),
                volume = node.path("evol").asText("0").toLongOrNull() ?: 0L,
            )
        }
    }

    /** 실측 확인(2026-09-29): output1 필드명이 아래와 정확히 일치. docs/kis-api/README.md 참고. */
    override fun filledPrice(
        market: Market,
        orderNo: String,
    ): BigDecimal? {
        if (market.isOverseas) return null
        val today = LocalDate.now(KST).format(DATE_FORMAT)
        val res =
            api.get(
                "/uapi/domestic-stock/v1/trading/inquire-daily-ccld",
                "VTTC0081R",
                accountParams() +
                    mapOf(
                        "INQR_STRT_DT" to today,
                        "INQR_END_DT" to today,
                        "SLL_BUY_DVSN_CD" to "00",
                        "PDNO" to "",
                        // 체결분만 (01) — 미체결/취소는 목표가가 아니므로 제외
                        "CCLD_DVSN" to "01",
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
        val row = res.path("output1").firstOrNull { it.path("odno").asText() == orderNo } ?: return null
        val filledQty = row.path("tot_ccld_qty").asText("0").toIntOrNull() ?: 0
        if (filledQty <= 0) return null
        return row.decimal("avg_prvs")
    }

    private fun accountParams() = mapOf("CANO" to properties.accountNumber, "ACNT_PRDT_CD" to properties.accountProductCode)

    private fun Market.quoteExchangeCode() =
        when (this) {
            Market.NASDAQ -> "NAS"
            Market.NYSE -> "NYS"
            Market.AMEX -> "AMS"
            Market.KR -> error("KR has no overseas exchange code")
        }

    private fun Market.orderExchangeCode() =
        when (this) {
            Market.NASDAQ -> "NASD"
            Market.NYSE -> "NYSE"
            Market.AMEX -> "AMEX"
            Market.KR -> error("KR has no overseas exchange code")
        }

    private fun String.toUsMarket() =
        when (this) {
            "NYSE", "NYS" -> Market.NYSE
            "AMEX", "AMS" -> Market.AMEX
            else -> Market.NASDAQ
        }

    /** 원화는 정수, 달러는 소수 둘째 자리. */
    private fun BigDecimal.priceText(market: Market): String =
        if (market.isOverseas) setScale(2, RoundingMode.HALF_UP).toPlainString() else setScale(0, RoundingMode.HALF_UP).toPlainString()

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

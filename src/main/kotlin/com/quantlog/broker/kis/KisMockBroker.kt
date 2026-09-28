package com.quantlog.broker.kis

import com.fasterxml.jackson.databind.JsonNode
import com.quantlog.broker.BrokerClient
import com.quantlog.broker.BuyingPower
import com.quantlog.broker.Holding
import com.quantlog.broker.Market
import com.quantlog.broker.OrderReceipt
import com.quantlog.broker.OrderRequest
import com.quantlog.broker.Side
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 한국투자증권 모의투자 구현체. 스펙 출처: docs/kis-api/examples (TR ID 는 모의투자용 V 접두).
 * 응답 필드명은 예제 코드에서 확인되지 않은 부분이 있어 첫 실행 시 KIS_MOCK_LOG_RAW=true 로 원문을 확인한다.
 */
@Component
class KisMockBroker(
    private val api: KisApiClient,
    private val properties: KisProperties,
) : BrokerClient {
    override fun currentPrice(
        market: Market,
        symbol: String,
    ): BigDecimal =
        if (market.isOverseas) {
            val res =
                api.get(
                    "/uapi/overseas-price/v1/quotations/price",
                    "HHDFS00000300",
                    mapOf("AUTH" to "", "EXCD" to market.quoteExchangeCode(), "SYMB" to symbol),
                )
            res.path("output").decimal("last")
        } else {
            val res =
                api.get(
                    "/uapi/domestic-stock/v1/quotations/inquire-price",
                    "FHKST01010100",
                    mapOf("FID_COND_MRKT_DIV_CODE" to "J", "FID_INPUT_ISCD" to symbol),
                )
            res.path("output").decimal("stck_prpr")
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
}

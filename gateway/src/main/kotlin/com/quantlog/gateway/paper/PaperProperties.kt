package com.quantlog.gateway.paper

import org.springframework.boot.context.properties.ConfigurationProperties
import java.math.BigDecimal

/**
 * 모킹 체결 계좌 설정. 증권사 수수료는 0으로 둔다(2026-10-02 사용자 결정). 제세금만 반영한다 — 요율은 2026-10-02 웹 확인:
 * 국내 주식 매도 증권거래세 0.20%(코스피 0.05+농특세 0.15, 코스닥 0.20, 2026-01-01~, ETF 는 면제),
 * 요율이 바뀌면 여기를 고친다.
 */
@ConfigurationProperties(prefix = "quantlog.broker.paper")
data class PaperProperties(
    val initialCashKrw: BigDecimal = BigDecimal("10000000"),
    val krStockSellTaxPercent: BigDecimal = BigDecimal("0.2"),
)

package com.quantlog.position

import com.quantlog.broker.Market
import com.quantlog.broker.Side
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant

/**
 * 브로커에 접수된 주문 1건의 기록 (매수/매도). `orderPrice`는 실제 체결가가 아니라 제출한 지정가다 —
 * 체결 내역을 별도로 조회해 정확한 체결가로 갱신하는 것은 이후 과제 (기획서 11.4의 Order/Trade 분리 참고).
 * 실현손익은 저장하지 않는다 — FIFO로 그때그때 계산한다 (PortfolioService).
 */
@Entity
@Table(name = "trade")
class Trade(
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val market: Market,
    @Column(nullable = false, length = 20)
    val symbol: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    val side: Side,
    @Column(nullable = false)
    val quantity: Int,
    @Column(name = "order_price", nullable = false, precision = 19, scale = 6)
    val orderPrice: BigDecimal,
    @Column(name = "order_no", nullable = false, length = 40)
    val orderNo: String,
    @Column(nullable = false, length = 500)
    val message: String,
    /** 왜 이 주문을 냈는지. AI 진입 판단 루프가 생기기 전까지는 규칙/수동 사유를 그대로 적는다. */
    @Column(length = 500)
    val reason: String? = null,
    @Column(name = "executed_at", nullable = false)
    val executedAt: Instant = Instant.now(),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set
}

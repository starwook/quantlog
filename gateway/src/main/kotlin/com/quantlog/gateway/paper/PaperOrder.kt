package com.quantlog.gateway.paper

import com.quantlog.gateway.broker.Market
import com.quantlog.gateway.broker.Side
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
 * 모킹 체결 1건. 주문 즉시 전량 체결되므로 주문 = 체결이다. 모킹 계좌의 현금·보유 수량·평단은 저장하지 않고
 * 이 기록을 순서대로 다시 계산해서 구한다(재시작해도 유지). id 가 곧 주문번호다.
 * `fillPrice` 는 수수료가 반영된 실효 체결가다(매수는 더 비싸게, 매도는 더 싸게).
 */
@Entity
@Table(name = "paper_order")
class PaperOrder(
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
    @Column(name = "fill_price", nullable = false, precision = 19, scale = 6)
    val fillPrice: BigDecimal,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set
}

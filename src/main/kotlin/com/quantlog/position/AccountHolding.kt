package com.quantlog.position

import com.quantlog.broker.Market
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.math.BigDecimal
import java.time.Instant

/**
 * KIS 계좌 잔고의 사본 (종목당 1행). 수량·평단·현재가는 [HoldingSyncService] 가 KIS 잔고를 받을 때마다 그대로 덮어쓴다.
 * 보유 현황의 기준은 매매 기록(Trade) FIFO 계산이 아니라 이 테이블이다 — 증권사 앱에서 직접 거래한 물량도 잔고에는 있기 때문이다.
 */
@Entity
@Table(name = "account_holding", uniqueConstraints = [UniqueConstraint(columnNames = ["market", "symbol"])])
class AccountHolding(
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val market: Market,
    @Column(nullable = false, length = 20)
    val symbol: String,
    quantity: Int,
    avgCost: BigDecimal,
    currentPrice: BigDecimal,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @Column(nullable = false)
    var quantity: Int = quantity
        private set

    @Column(name = "avg_cost", nullable = false, precision = 19, scale = 6)
    var avgCost: BigDecimal = avgCost
        private set

    @Column(name = "current_price", nullable = false, precision = 19, scale = 6)
    var currentPrice: BigDecimal = currentPrice
        private set

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
        private set

    fun update(
        quantity: Int,
        avgCost: BigDecimal,
        currentPrice: BigDecimal,
    ) {
        this.quantity = quantity
        this.avgCost = avgCost
        this.currentPrice = currentPrice
        this.updatedAt = Instant.now()
    }
}

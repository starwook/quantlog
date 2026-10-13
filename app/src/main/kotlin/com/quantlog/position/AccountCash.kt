package com.quantlog.position

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant

/**
 * 계좌에 남은 현금의 사본 (통화당 1행). 보유 종목([AccountHolding])과 별개의 값이다.
 * 체결이 반영될 때마다 [AccountCashService] 가 체결금액만큼 더하고 빼며, KIS 잔고가 올 때마다 증권사 값으로 바로잡는다.
 * 아직 체결되지 않은 매수 주문이 잡고 있는 돈은 여기서 빼지 않는다 — 주문가능금액을 물을 때 따로 뺀다([AccountCashService.orderable]).
 */
@Entity
@Table(name = "account_cash")
class AccountCash(
    @Column(nullable = false, unique = true, length = 10)
    val currency: String,
    amount: BigDecimal,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @Column(nullable = false, precision = 19, scale = 2)
    var amount: BigDecimal = amount
        private set

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
        private set

    fun update(amount: BigDecimal) {
        this.amount = amount
        this.updatedAt = Instant.now()
    }
}

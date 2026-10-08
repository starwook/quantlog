package com.quantlog.gateway.record

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.springframework.data.jpa.repository.JpaRepository
import java.math.BigDecimal
import java.time.Instant

/**
 * KIS 잔고를 받은 그대로 둔 최신 스냅샷(종목당 1행). 해석은 앱이 한다(앱이 읽는 계약 — docs/contracts/broker_balance.md).
 * [BrokerBalanceMeta] 가 이 스냅샷의 회차 번호와 "KIS 를 부르기 시작한 시각"을 갖는다.
 */
@Entity
@Table(name = "broker_balance", uniqueConstraints = [UniqueConstraint(columnNames = ["market", "symbol"])])
class BrokerBalance(
    @Column(nullable = false, length = 20)
    val market: String,
    @Column(nullable = false, length = 20)
    val symbol: String,
    @Column(nullable = false, length = 100)
    var name: String,
    @Column(nullable = false, precision = 19, scale = 6)
    var quantity: BigDecimal,
    @Column(name = "average_price", nullable = false, precision = 19, scale = 6)
    var averagePrice: BigDecimal,
    @Column(name = "current_price", nullable = false, precision = 19, scale = 6)
    var currentPrice: BigDecimal,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set
}

interface BrokerBalanceRepository : JpaRepository<BrokerBalance, Long>

/** 잔고 스냅샷 한 회차의 메타(1행). [fetchedAt] 은 KIS 를 부르기 **전** 시각 — 조회 도중 낸 주문을 "반영됨"으로 착각하지 않으려는 기준이다. */
@Entity
@Table(name = "broker_balance_meta")
class BrokerBalanceMeta(
    @Id
    val id: Long = 1,
    @Column(nullable = false)
    var seq: Long = 0,
    @Column(name = "fetched_at", nullable = false)
    var fetchedAt: Instant = Instant.EPOCH,
    @Column(name = "completed_at", nullable = false)
    var completedAt: Instant = Instant.EPOCH,
)

interface BrokerBalanceMetaRepository : JpaRepository<BrokerBalanceMeta, Long>

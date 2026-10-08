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
 * 증권사로 나간 주문·취소 요청의 기록. 앱이 요청마다 붙이는 요청 ID([requestId])로 중복을 막고, **호출 전에 SENDING 으로 먼저 쓴다** —
 * 응답을 못 받아도(타임아웃·크래시) "보내려 했다"는 기록이 남아, 같은 요청이 다시 와도 맹목적으로 또 보내지 않는다.
 */
@Entity
@Table(name = "broker_order", uniqueConstraints = [UniqueConstraint(columnNames = ["request_id"])])
class BrokerOrder(
    @Column(name = "request_id", nullable = false, length = 80)
    val requestId: String,
    /** PLACE / CANCEL. */
    @Column(nullable = false, length = 10)
    val kind: String,
    @Column(nullable = false, length = 20)
    val market: String,
    @Column(nullable = false, length = 20)
    val symbol: String,
    @Column(length = 10)
    val side: String?,
    @Column(nullable = false)
    val quantity: Int,
    @Column(name = "limit_price", nullable = false, precision = 19, scale = 6)
    val limitPrice: BigDecimal,
    /** 취소일 때 대상 주문번호. */
    @Column(name = "target_order_no", length = 40)
    val targetOrderNo: String?,
    @Column(nullable = false, length = 12, columnDefinition = "varchar(12)")
    var status: String,
    @Column(name = "order_no", length = 40)
    var orderNo: String? = null,
    @Column(name = "branch_no", length = 40)
    var branchNo: String? = null,
    @Column(length = 500)
    var message: String? = null,
    @Column(length = 500)
    var error: String? = null,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    companion object {
        const val SENDING = "SENDING"
        const val SENT = "SENT"
        const val FAILED = "FAILED"
    }
}

interface BrokerOrderRepository : JpaRepository<BrokerOrder, Long> {
    fun findByRequestId(requestId: String): BrokerOrder?
}

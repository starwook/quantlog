package com.quantlog.gateway.record

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

/**
 * 한투로 나간 주문·취소 요청과 응답의 원문 기록. 앱이 요청마다 붙이는 요청 ID([requestId])로 중복을 막고, **호출 전에 SENDING 으로 먼저 쓴다** —
 * 응답을 못 받아도(타임아웃·크래시) "보내려 했다"는 기록이 남아, 같은 요청이 다시 와도 맹목적으로 또 보내지 않는다.
 * [requestBody] 는 앱이 보낸 그대로(계좌번호를 끼우기 전)이고, [responseBody] 는 한투 응답 원문이다. 해석은 앱이 한다.
 */
@Entity
@Table(name = "kis_order", uniqueConstraints = [UniqueConstraint(columnNames = ["request_id"])])
class KisOrder(
    @Column(name = "request_id", nullable = false, length = 80)
    val requestId: String,
    @Column(name = "tr_id", nullable = false, length = 20)
    val trId: String,
    @Column(nullable = false, length = 200)
    val path: String,
    @Column(name = "request_body", nullable = false, columnDefinition = "text")
    val requestBody: String,
    @Column(nullable = false, length = 12, columnDefinition = "varchar(12)")
    var status: String,
    /** 한투가 돌려준 HTTP 상태. 응답을 받은 뒤(DONE)에만 있다. */
    @Column(name = "http_status")
    var httpStatus: Int? = null,
    @Column(name = "response_body", columnDefinition = "text")
    var responseBody: String? = null,
    /** 한투에서 응답을 받지 못한 이유(FAILED). */
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
        const val DONE = "DONE"
        const val FAILED = "FAILED"
    }
}

interface KisOrderRepository : JpaRepository<KisOrder, Long> {
    fun findByRequestId(requestId: String): KisOrder?
}

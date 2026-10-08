package com.quantlog.gateway.record

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

/**
 * 한투 잔고조회(주식잔고조회) 응답을 **원문 그대로** 회차마다 한 줄씩 쌓는다. 게이트웨이는 해석하지 않고, 앱이 한투 문서대로 읽어 보유 현황에 맞춘다.
 * [fetchedAt] 은 한투를 부르기 **전** 시각 — 조회 도중 낸 주문을 "반영됨"으로 착각하지 않으려는 기준이다. 앱이 읽는 계약(docs/contracts/README.md).
 * 앱은 가장 큰 id 한 줄만 쓰므로 오래된 줄은 지운다([KisBalanceStore]).
 */
@Entity
@Table(name = "kis_balance")
class KisBalance(
    @Column(name = "fetched_at", nullable = false)
    val fetchedAt: Instant,
    @Column(name = "received_at", nullable = false)
    val receivedAt: Instant,
    @Column(nullable = false, columnDefinition = "mediumtext")
    val body: String,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set
}

interface KisBalanceRepository : JpaRepository<KisBalance, Long> {
    fun deleteByReceivedAtBefore(cutoff: Instant): Long
}

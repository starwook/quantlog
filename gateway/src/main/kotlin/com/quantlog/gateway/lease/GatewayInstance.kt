package com.quantlog.gateway.lease

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * 게이트웨이 인스턴스 1행. 두 가지를 한다: (1) **단일 실행 잠금** — 증권사 웹소켓은 키당 1세션이라 게이트웨이가 둘 뜨면 서로 연결을 빼앗는다.
 * [leaseUntil] 이 안 지난 다른 인스턴스가 있으면 새 인스턴스는 뜨지 않는다. (2) **하트비트** — 앱이 이 행을 읽어 게이트웨이가 살아 있는지,
 * 웹소켓이 언제 다시 붙었는지(체결 보충을 돌릴 신호)를 안다. 앱이 읽는 계약이다(docs/contracts/gateway_instance.md).
 */
@Entity
@Table(name = "gateway_instance")
class GatewayInstance(
    @Id
    val id: Long = 1,
    @Column(name = "instance_id", nullable = false, length = 64)
    var instanceId: String = "",
    @Column(name = "started_at", nullable = false)
    var startedAt: Instant = Instant.EPOCH,
    @Column(name = "heartbeat_at", nullable = false)
    var heartbeatAt: Instant = Instant.EPOCH,
    @Column(name = "lease_until", nullable = false)
    var leaseUntil: Instant = Instant.EPOCH,
    @Column(name = "ws_connected_at")
    var wsConnectedAt: Instant? = null,
    @Column(name = "live_symbols", length = 2000)
    var liveSymbols: String? = null,
    @Column(name = "contract_version", nullable = false)
    var contractVersion: Int = 0,
)

interface GatewayInstanceRepository : JpaRepository<GatewayInstance, Long> {
    @Modifying
    @Transactional
    @Query(
        "update GatewayInstance g set g.instanceId = :me, g.startedAt = :startedAt, g.heartbeatAt = :now, g.leaseUntil = :until, " +
            "g.contractVersion = :version, g.wsConnectedAt = null, g.liveSymbols = null " +
            "where g.id = 1 and (g.instanceId = :me or g.leaseUntil < :now)",
    )
    fun tryAcquire(
        @Param("me") me: String,
        @Param("startedAt") startedAt: Instant,
        @Param("now") now: Instant,
        @Param("until") until: Instant,
        @Param("version") version: Int,
    ): Int

    @Modifying
    @Transactional
    @Query(
        "update GatewayInstance g set g.heartbeatAt = :now, g.leaseUntil = :until, g.wsConnectedAt = :wsAt, g.liveSymbols = :live " +
            "where g.id = 1 and g.instanceId = :me",
    )
    fun renew(
        @Param("me") me: String,
        @Param("now") now: Instant,
        @Param("until") until: Instant,
        @Param("wsAt") wsConnectedAt: Instant?,
        @Param("live") liveSymbols: String?,
    ): Int
}

package com.quantlog.gatewayclient

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.Immutable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.Instant

// 게이트웨이가 쓰는 테이블을 앱이 **읽기 전용으로** 읽는 선언이다(원문 그대로 쌓인 한투 응답이라 해석은 앱의 `broker/Kis*Parser` 가 한다). 게이트웨이와 코드를 공유하지 않으므로 같은 컬럼을 앱 쪽에 따로 적고,
// 계약 테스트(docs/contracts/)가 둘이 같은지 확인한다. 컬럼은 추가만 되므로 앱이 아는 컬럼이 늘 있다.
// 앱도 ddl-auto 라서 앱이 먼저 뜨면 이 선언으로 테이블이 만들어진다 — 게이트웨이가 IDENTITY 로 insert 하는 테이블은 여기서도 id 를 IDENTITY 로 선언해
// AUTO_INCREMENT 가 빠지지 않게 한다(빠지면 게이트웨이 insert 가 "Field 'id' doesn't have a default value" 로 실패한다).

/** 체결통보 원장(`kis_broker_fill`) 한 줄 — 한투 체결통보 원문(`^` 구분 한 건, 계좌 식별 칸은 비움). docs/contracts/tables.md. */
@Entity
@Immutable
@Table(name = "kis_broker_fill")
class KisBrokerFillRow(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    @Column(name = "received_at", nullable = false)
    val receivedAt: Instant = Instant.EPOCH,
    @Column(name = "tr_id", nullable = false, length = 20)
    val trId: String = "",
    @Column(nullable = false, length = 2000)
    val body: String = "",
)

interface KisBrokerFillRowRepository : JpaRepository<KisBrokerFillRow, Long> {
    fun findTop100ByIdGreaterThanOrderByIdAsc(id: Long): List<KisBrokerFillRow>

    @Query("select max(n.id) from KisBrokerFillRow n")
    fun maxId(): Long?
}

/** 잔고 원문(`kis_balance`) 한 줄 — 한투 주식잔고조회 응답 JSON 원문. [fetchedAt] 은 게이트웨이가 한투를 부르기 **전** 시각이다. docs/contracts/README.md. */
@Entity
@Immutable
@Table(name = "kis_balance")
class KisBalanceRow(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    @Column(name = "fetched_at", nullable = false)
    val fetchedAt: Instant = Instant.EPOCH,
    @Column(name = "received_at", nullable = false)
    val receivedAt: Instant = Instant.EPOCH,
    @Column(nullable = false, columnDefinition = "mediumtext")
    val body: String = "",
)

interface KisBalanceRowRepository : JpaRepository<KisBalanceRow, Long> {
    fun findTopByOrderByIdDesc(): KisBalanceRow?
}

/** 분봉 원문(`kis_minute_chart`) 한 줄 — 한투 주식당일분봉조회 응답 JSON 원문(종목마다 한 번 받을 때 한 줄). docs/contracts/README.md. */
@Entity
@Immutable
@Table(name = "kis_minute_chart")
class KisMinuteChartRow(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    @Column(nullable = false, length = 20)
    val market: String = "",
    @Column(nullable = false, length = 20)
    val symbol: String = "",
    @Column(name = "received_at", nullable = false)
    val receivedAt: Instant = Instant.EPOCH,
    @Column(nullable = false, columnDefinition = "mediumtext")
    val body: String = "",
)

interface KisMinuteChartRowRepository : JpaRepository<KisMinuteChartRow, Long> {
    fun findTop200ByIdGreaterThanOrderByIdAsc(id: Long): List<KisMinuteChartRow>
}

/** 앱이 쓰는 계약 테이블(`held_symbol`) — 지금 보유 중인 종목. 게이트웨이는 잔고 원문을 해석하지 않으므로 앱이 알려 준다. docs/contracts/README.md. */
@Entity
@Table(name = "held_symbol", uniqueConstraints = [UniqueConstraint(columnNames = ["market", "symbol"])])
class HeldSymbolRow(
    @Column(nullable = false, length = 20)
    val market: String,
    @Column(nullable = false, length = 20)
    val symbol: String,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set
}

interface HeldSymbolRowRepository : JpaRepository<HeldSymbolRow, Long>

/** 게이트웨이 인스턴스 하트비트(`gateway_instance`, 1행) — docs/contracts/gateway_instance.md. */
@Entity
@Immutable
@Table(name = "gateway_instance")
class GatewayInstanceRow(
    @Id
    val id: Long = 1,
    @Column(name = "instance_id", nullable = false, length = 64)
    val instanceId: String = "",
    @Column(name = "started_at", nullable = false)
    val startedAt: Instant = Instant.EPOCH,
    @Column(name = "heartbeat_at", nullable = false)
    val heartbeatAt: Instant = Instant.EPOCH,
    @Column(name = "lease_until", nullable = false)
    val leaseUntil: Instant = Instant.EPOCH,
    @Column(name = "ws_connected_at")
    val wsConnectedAt: Instant? = null,
    @Column(name = "live_symbols", length = 2000)
    val liveSymbols: String? = null,
    @Column(name = "contract_version", nullable = false)
    val contractVersion: Int = 0,
)

interface GatewayInstanceRowRepository : JpaRepository<GatewayInstanceRow, Long>

/** 앱이 쓰는 계약 테이블(`watch_symbol`) — 게이트웨이가 구독·수집할 종목. docs/contracts/watch_symbol.md. */
@Entity
@Table(name = "watch_symbol", uniqueConstraints = [UniqueConstraint(columnNames = ["market", "symbol"])])
class WatchSymbolRow(
    @Column(nullable = false, length = 20)
    val market: String,
    @Column(nullable = false, length = 20)
    val symbol: String,
    @Column(nullable = false)
    var etf: Boolean = false,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set
}

interface WatchSymbolRowRepository : JpaRepository<WatchSymbolRow, Long> {
    fun findByMarketAndSymbol(
        market: String,
        symbol: String,
    ): WatchSymbolRow?
}

/** 앱이 체결 원장을 어디까지 처리했는지(`broker_projection_cursor`). 앱이 재시작돼도 이 ID 다음부터 이어서 처리한다. */
@Entity
@Table(name = "broker_projection_cursor")
class ProjectionCursor(
    @Id
    @Column(length = 40)
    val name: String = "",
    @Column(name = "last_id", nullable = false)
    val lastId: Long = 0,
)

interface ProjectionCursorRepository : JpaRepository<ProjectionCursor, String>

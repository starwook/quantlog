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
import java.math.BigDecimal
import java.time.Instant

// 게이트웨이가 쓰는 테이블을 앱이 **읽기 전용으로** 읽는 선언이다. 게이트웨이와 코드를 공유하지 않으므로 같은 컬럼을 앱 쪽에 따로 적고,
// 계약 테스트(docs/contracts/)가 둘이 같은지 확인한다. 컬럼은 추가만 되므로 앱이 아는 컬럼이 늘 있다.
// 앱도 ddl-auto 라서 앱이 먼저 뜨면 이 선언으로 테이블이 만들어진다 — 게이트웨이가 IDENTITY 로 insert 하는 테이블은 여기서도 id 를 IDENTITY 로 선언해
// AUTO_INCREMENT 가 빠지지 않게 한다(빠지면 게이트웨이 insert 가 "Field 'id' doesn't have a default value" 로 실패한다).

/** 체결통보 원장(`broker_fill`) 한 줄 — docs/contracts/broker_fill.md. */
@Entity
@Immutable
@Table(name = "broker_fill")
class BrokerFillRow(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    @Column(name = "received_at", nullable = false)
    val receivedAt: Instant = Instant.EPOCH,
    @Column(nullable = false, length = 20)
    val symbol: String = "",
    @Column(name = "order_no", nullable = false, length = 40)
    val orderNo: String = "",
    @Column(name = "original_order_no", length = 40)
    val originalOrderNo: String? = null,
    @Column(name = "sell_buy_code", nullable = false, length = 8)
    val sellBuyCode: String = "",
    @Column(name = "filled_flag", nullable = false, length = 8)
    val filledFlag: String = "",
    @Column(name = "accept_flag", length = 8)
    val acceptFlag: String? = null,
    @Column(name = "refuse_flag", length = 8)
    val refuseFlag: String? = null,
    @Column(name = "filled_quantity", precision = 19, scale = 6)
    val filledQuantity: BigDecimal? = null,
    @Column(name = "filled_price", precision = 19, scale = 6)
    val filledPrice: BigDecimal? = null,
    @Column(name = "order_quantity", precision = 19, scale = 6)
    val orderQuantity: BigDecimal? = null,
    @Column(name = "order_price", precision = 19, scale = 6)
    val orderPrice: BigDecimal? = null,
    @Column(name = "notice_time", nullable = false, length = 16)
    val noticeTime: String = "",
)

interface BrokerFillRowRepository : JpaRepository<BrokerFillRow, Long> {
    fun findTop100ByIdGreaterThanOrderByIdAsc(id: Long): List<BrokerFillRow>

    fun findAllByOrderNoAndIdLessThanEqualOrderByIdAsc(
        orderNo: String,
        id: Long,
    ): List<BrokerFillRow>

    fun findAllByIdLessThanEqualOrderByIdAsc(id: Long): List<BrokerFillRow>

    @Query("select max(f.id) from BrokerFillRow f")
    fun maxId(): Long?
}

/** 잔고 스냅샷(`broker_balance`, 종목당 1행) — docs/contracts/broker_balance.md. */
@Entity
@Immutable
@Table(name = "broker_balance", uniqueConstraints = [UniqueConstraint(columnNames = ["market", "symbol"])])
class BrokerBalanceRow(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    @Column(nullable = false, length = 20)
    val market: String = "",
    @Column(nullable = false, length = 20)
    val symbol: String = "",
    @Column(nullable = false, length = 100)
    val name: String = "",
    @Column(nullable = false, precision = 19, scale = 6)
    val quantity: BigDecimal = BigDecimal.ZERO,
    @Column(name = "average_price", nullable = false, precision = 19, scale = 6)
    val averagePrice: BigDecimal = BigDecimal.ZERO,
    @Column(name = "current_price", nullable = false, precision = 19, scale = 6)
    val currentPrice: BigDecimal = BigDecimal.ZERO,
    @Column(name = "updated_at", nullable = false)
    val updatedAt: Instant = Instant.EPOCH,
)

interface BrokerBalanceRowRepository : JpaRepository<BrokerBalanceRow, Long>

/** 잔고 스냅샷 회차 메타(`broker_balance_meta`, 1행). [fetchedAt] 은 게이트웨이가 KIS 를 부르기 **전** 시각이다. */
@Entity
@Immutable
@Table(name = "broker_balance_meta")
class BrokerBalanceMetaRow(
    @Id
    val id: Long = 1,
    @Column(nullable = false)
    val seq: Long = 0,
    @Column(name = "fetched_at", nullable = false)
    val fetchedAt: Instant = Instant.EPOCH,
    @Column(name = "completed_at", nullable = false)
    val completedAt: Instant = Instant.EPOCH,
)

interface BrokerBalanceMetaRowRepository : JpaRepository<BrokerBalanceMetaRow, Long>

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

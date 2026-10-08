package com.quantlog.position

import com.quantlog.broker.Market
import com.quantlog.broker.Side
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import org.springframework.data.jpa.repository.JpaRepository
import java.math.BigDecimal
import java.time.Instant

/** 체결 원장 한 줄이 어디서 왔는지. */
enum class FillSource {
    /** 실시간 체결통보. 체결 시각·그 시점 평단이 정확하다. */
    NOTICE,

    /** 체결통보를 놓쳐서 KIS 체결내역 조회로 나중에 채운 줄. 체결 시각은 채운 시각이고 체결 직전 평단은 모른다. */
    REST_BACKFILL,
}

/**
 * **옛 체결 원장 — 더 이상 쓰지 않고 과거 기록 표시용으로 읽기만 한다**(2026-10-08). 체결 내역은 게이트웨이 원장(`broker_fill`)
 * 하나뿐이고([OrderFills]), 매도 손익 기준인 체결 직전 평단은 매매 기록([Trade.avgCostBefore])에 둔다. 이 테이블에는 서버 분리 전
 * (앱이 체결통보를 직접 받던 때)와 분리 직후 앱이 `broker_fill` 을 옮겨 적은 줄이 남아 있다 — `broker_fill` 에 같은 주문이 있으면 그쪽을 쓴다.
 */
@Immutable
@Entity
@Table(name = "trade_fill", indexes = [Index(name = "idx_trade_fill_order", columnList = "market,order_no")])
class TradeFill(
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val market: Market,
    @Column(nullable = false, length = 20)
    val symbol: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    val side: Side,
    @Column(name = "order_no", nullable = false, length = 40)
    val orderNo: String,
    /** 이 체결 건의 수량(누적 아님). */
    @Column(nullable = false)
    val quantity: Int,
    @Column(nullable = false, precision = 19, scale = 6)
    val price: BigDecimal,
    /** 이 체결 직전의 보유 평단. 보유 사본에 종목이 없었으면 null(손익을 확정할 수 없다). */
    @Column(name = "avg_cost_before", precision = 19, scale = 6)
    val avgCostBefore: BigDecimal?,
    @Column(name = "filled_at", nullable = false)
    val filledAt: Instant,
    // 컬럼을 varchar 로 고정한다. MySQL 에서 enum 컬럼은 `ddl-auto: update` 가 값 추가를 반영하지 않아, enum 에 값을 더하면 서버 DB 에서
    // "Data truncated" 로 저장이 실패한다(2026-10-08 REST_BACKFILL 추가 때 겪음).
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, columnDefinition = "varchar(20)")
    val source: FillSource = FillSource.NOTICE,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set
}

/** 옛 원장 읽기 전용. 새로 쓰지 않는다. */
interface TradeFillRepository : JpaRepository<TradeFill, Long>

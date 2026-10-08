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
import org.springframework.data.jpa.repository.JpaRepository
import java.math.BigDecimal
import java.time.Instant

/** 체결 원장 한 줄이 어디서 왔는지. */
enum class FillSource {
    /** 실시간 체결통보. 체결 시각·그 시점 평단이 정확하다. */
    NOTICE,
}

/**
 * 체결 원장 — 체결통보 1건 = 1줄 (docs/체결-원장-설계.md). 주문 기록([Trade])은 주문 단위라 부분체결을 못 담고,
 * 실현손익을 FIFO 로 다시 계산하면 짝 없는 옛 매수 기록 때문에 틀어졌다(2026-10-08). 그래서 체결이 일어난 그 순간의
 * 평단([avgCostBefore])을 같이 박아 두고, 손익은 여기서 확정한다 — 다시 계산하지 않는다.
 */
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
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val source: FillSource = FillSource.NOTICE,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set
}

interface TradeFillRepository : JpaRepository<TradeFill, Long>

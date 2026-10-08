package com.quantlog.gateway.record

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.math.BigDecimal
import java.time.Instant

/**
 * 증권사가 실시간으로 밀어 준 체결통보를 **받은 그대로** 한 줄씩 쌓는 원장. 게이트웨이는 해석하지 않는다 — 접수 통보도 체결 통보도 다 쌓고,
 * 평단·수량 계산은 앱이 이 테이블을 읽어서 한다. 계좌번호·고객 ID·계좌명 같은 식별 필드는 애초에 파싱 단계에서 버린다.
 * 앱이 읽는 계약이다(docs/contracts/broker_fill.md) — 컬럼은 추가만 하고 바꾸거나 지우지 않는다.
 */
@Entity
@Table(name = "broker_fill", indexes = [Index(name = "idx_broker_fill_order", columnList = "order_no")])
class BrokerFill(
    @Column(name = "received_at", nullable = false)
    val receivedAt: Instant,
    @Column(nullable = false, length = 20)
    val symbol: String,
    @Column(name = "order_no", nullable = false, length = 40)
    val orderNo: String,
    @Column(name = "original_order_no", length = 40)
    val originalOrderNo: String?,
    /** SELN_BYOV_CLS 원문. 국내는 "01" 매도 / "02" 매수. */
    @Column(name = "sell_buy_code", nullable = false, length = 8)
    val sellBuyCode: String,
    /** CNTG_YN: "2" 면 체결통보, "1" 이면 접수·정정·취소·거부 통보. */
    @Column(name = "filled_flag", nullable = false, length = 8)
    val filledFlag: String,
    @Column(name = "accept_flag", length = 8)
    val acceptFlag: String?,
    @Column(name = "refuse_flag", length = 8)
    val refuseFlag: String?,
    @Column(name = "filled_quantity", precision = 19, scale = 6)
    val filledQuantity: BigDecimal?,
    @Column(name = "filled_price", precision = 19, scale = 6)
    val filledPrice: BigDecimal?,
    @Column(name = "order_quantity", precision = 19, scale = 6)
    val orderQuantity: BigDecimal?,
    @Column(name = "order_price", precision = 19, scale = 6)
    val orderPrice: BigDecimal?,
    /** STCK_CNTG_HOUR 원문(HHmmss). */
    @Column(name = "notice_time", nullable = false, length = 16)
    val noticeTime: String,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set
}

interface BrokerFillRepository : JpaRepository<BrokerFill, Long>

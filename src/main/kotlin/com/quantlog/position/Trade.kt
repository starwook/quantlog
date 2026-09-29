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
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant

/**
 * 브로커에 접수된 주문 1건의 기록 (매수/매도). `orderPrice`는 제출한 지정가, `filledPrice`는 실제 체결가(국내만,
 * KIS 체결내역 조회로 채움 — 2026-09-29 도입, 이전엔 지정가를 체결가처럼 화면에 보여준 적이 있었다).
 * 실현손익은 저장하지 않는다 — FIFO로 그때그때 계산한다 (PortfolioService).
 */
@Entity
@Table(name = "trade")
class Trade(
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val market: Market,
    @Column(nullable = false, length = 20)
    val symbol: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    val side: Side,
    @Column(nullable = false)
    val quantity: Int,
    @Column(name = "order_price", nullable = false, precision = 19, scale = 6)
    val orderPrice: BigDecimal,
    @Column(name = "order_no", nullable = false, length = 40)
    val orderNo: String,
    @Column(nullable = false, length = 500)
    val message: String,
    /** 왜 이 주문을 냈는지. AI 진입 판단 루프가 생기기 전까지는 규칙/수동 사유를 그대로 적는다. */
    @Column(length = 500)
    val reason: String? = null,
    /** 생성 시점의 체결가. 코틀린 주 생성자 파라미터엔 커스텀 setter 가시성을 못 붙여서, 실제 보관은
     * 아래 본문의 [filledPrice] 프로퍼티가 한다 — 이 값은 그 초기값일 뿐이다. */
    initialFilledPrice: BigDecimal? = null,
    @Column(name = "executed_at", nullable = false)
    val executedAt: Instant = Instant.now(),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    /**
     * 실제 체결가(평균). 국내만 채워짐(KIS 체결내역 조회) — 해외는 아직 null, [orderPrice] 로 대체.
     * 2026-09-29 실측: 지정가와 체결가가 꽤 다를 수 있었다(삼성전자 273,000 지정 → 272,000 체결).
     * 실현손익·평단 계산은 이 값을 우선한다 (PortfolioService). 주문 직후 한 번 조회해서 못 구했으면
     * null 로 남는데, [TradeReconciler] 가 KIS 를 다시 물어봐서 나중에 채운다 — KIS 가 "정답"이고
     * 우리 DB는 그걸 따라가는 사본일 뿐이다.
     */
    @Column(name = "filled_price", precision = 19, scale = 6)
    var filledPrice: BigDecimal? = initialFilledPrice
        private set

    /** [TradeReconciler] 전용: 나중에 확인된 실제 체결가로 채운다. 한번 채워지면 되돌리지 않는다. */
    fun applyFilledPrice(price: BigDecimal) {
        if (filledPrice == null) filledPrice = price
    }
}

package com.quantlog.position

import com.quantlog.broker.Market
import com.quantlog.broker.OrderStatus
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
 * 브로커에 접수된 주문 1건의 기록 (매수/매도). `orderPrice`는 제출한 지정가, `filledPrice`는 실제 체결가(평균)로 체결 원장(`broker_fill`)에서 채운다.
 * 매도 손익은 [avgCostBefore](체결 직전 평단) 기준으로 확정한다(PortfolioService). 그 값이 없는 옛 주문만 FIFO 로 다시 계산한다.
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
    /** 국내 정정·취소에 쓰는 주문조직번호(주문 응답의 KRX_FWDG_ORD_ORGNO). 옛 기록은 null. */
    @Column(name = "branch_no", length = 20)
    val branchNo: String? = null,
    initialOpenConfirmed: Boolean = false,
    initialFilledQuantity: Int? = null,
    initialAvgCostBefore: BigDecimal? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    /**
     * 실제 체결가(평균). 체결 원장(`broker_fill`)의 이 주문 체결 누적으로 채운다([applyFill]). 아직 체결 전이면 null, [orderPrice] 로 대체.
     * 2026-09-29 실측: 지정가와 체결가가 꽤 다를 수 있었다(삼성전자 273,000 지정 → 272,000 체결).
     */
    @Column(name = "filled_price", precision = 19, scale = 6)
    var filledPrice: BigDecimal? = initialFilledPrice
        private set

    /**
     * 지금까지 체결된 수량(누적). 체결통보로 쌓는다. null 이면 옛 기록 — [filledPrice] 가 있으면 주문수량 전부 체결로 본다.
     * 주문수량보다 적으면 부분체결 중이다([OrderState.PARTIAL]).
     */
    @Column(name = "filled_quantity")
    var filledQuantity: Int? = initialFilledQuantity
        private set

    /**
     * 매도 주문의 체결 직전 보유 평단. 이 매도의 손익은 (체결가 − 이 값) × 체결수량으로 확정한다. 매도는 평단을 바꾸지 않아 이 주문의 체결
     * 모두에 같은 값이다. 매수 주문, 체결 전, 보유 사본에 종목이 없던 매도, 옛 기록은 null(손익을 짐작하지 않는다).
     */
    @Column(name = "avg_cost_before", precision = 19, scale = 6)
    var avgCostBefore: BigDecimal? = initialAvgCostBefore
        private set

    /** 체결 직전 평단을 처음 한 번만 적는다. 적었으면 true. */
    fun recordAvgCostBefore(average: BigDecimal): Boolean {
        if (avgCostBefore != null) return false
        avgCostBefore = average
        return true
    }

    /** 체결이 시작됐지만 주문수량만큼은 아직 안 찬 상태. */
    val partiallyFilled: Boolean
        get() = filledPrice != null && (filledQuantity ?: quantity) < quantity

    /** 체결 정보가 어디까지 채워져 있나. 동기화 불일치 보고용 문구다. */
    fun fillStateText(): String =
        when {
            filledPrice == null -> "체결가 없음"
            partiallyFilled -> "일부 체결 $filledQuantity/${quantity}주"
            else -> "체결 ${filledPrice?.stripTrailingZeros()?.toPlainString()}"
        }

    /** 취소된 주문. 체결되지 않았으므로 손익·사이클 계산과 체결가 재확인에서 빠진다. */
    @Column(nullable = false)
    var canceled: Boolean = false
        private set

    fun cancel() {
        canceled = true
    }

    /**
     * 증권사가 이 주문을 확인했고 아직 체결 전이라고 알려 준 상태(진짜 미체결). [filledPrice] 가 null 이어도 이게 false 면
     * 미체결인지 체결인지 아직 모르는 것(조회 반영 전)이다 — 화면에서 "체결 확인중"으로 따로 보여준다.
     */
    @Column(name = "open_confirmed", nullable = false)
    var openConfirmed: Boolean = initialOpenConfirmed
        private set

    /** 화면에 보여줄 주문 상태. 취소 > 체결 > 증권사 확인 미체결 > 체결 확인중 순으로 판정한다. */
    val state: OrderState
        get() =
            when {
                canceled -> OrderState.CANCELED
                partiallyFilled -> OrderState.PARTIAL
                filledPrice != null -> OrderState.FILLED
                openConfirmed -> OrderState.OPEN
                else -> OrderState.CHECKING
            }

    /**
     * 체결통보 누적치를 반영한다. 주문수량만큼 이미 찼거나 옛 기록(수량 null + 체결가 있음)이면 건드리지 않는다.
     * 수량이 늘었을 때만 바뀌고, 바뀐 게 있으면 true.
     */
    fun applyFill(
        averagePrice: BigDecimal,
        cumulativeQuantity: Int,
    ): Boolean {
        val filled = filledQuantity ?: if (filledPrice != null) quantity else 0
        if (filled >= quantity || cumulativeQuantity <= filled) return false
        filledPrice = averagePrice
        filledQuantity = minOf(cumulativeQuantity, quantity)
        openConfirmed = false
        return true
    }

    /** 증권사 체결조회 결과를 반영한다. 체결이 확인되면 되돌리지 않는다(부분체결이었다면 전량 체결로 올린다). 바뀐 게 있으면 true. */
    fun apply(status: OrderStatus): Boolean =
        when (status) {
            is OrderStatus.Filled ->
                (filledPrice == null || partiallyFilled).also {
                    if (it) {
                        filledPrice = status.price
                        filledQuantity = quantity
                    }
                    openConfirmed = false
                }
            OrderStatus.Open -> (filledPrice == null && !openConfirmed).also { if (it) openConfirmed = true }
            OrderStatus.Unknown -> false
        }
}

/**
 * 주문 한 건의 진행 상태. [OPEN] 은 증권사가 미체결로 확인한 것, [CHECKING] 은 아직 체결인지 미체결인지 모르는 것,
 * [PARTIAL] 은 일부만 체결된 것(나머지는 아직 호가창에 남아 있다).
 */
enum class OrderState { CHECKING, OPEN, PARTIAL, FILLED, CANCELED }

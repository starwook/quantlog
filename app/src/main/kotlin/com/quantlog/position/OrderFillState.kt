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
import jakarta.persistence.UniqueConstraint
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * 주문 하나의 체결 상태(`order_fill`, 앱 소유, 주문당 한 줄) — 앱이 "이 주문이 몇 주 얼마에 체결됐나"를 볼 때의 **유일한 기준**이다(2026-10-14).
 * 두 출처를 따로 적고 큰 쪽을 쓴다.
 * - **체결통보**([noticeQuantity]·[noticeAmount]): 게이트웨이 원장(`kis_broker_fill`)의 통보를 원장 줄 순서대로 한 번씩 더한 합. 빠르지만 한투 웹소켓이 끊긴 동안의 통보는 빠진다.
 * - **체결조회**([inquiryQuantity]·[inquiryAmount]): 한투 주식일별주문체결조회의 이 주문 누적(`tot_ccld_qty`·`tot_ccld_amt`). 느리지만 빠지지 않는다.
 *
 * 주문의 체결 수량은 늘기만 하므로 수량이 큰 쪽이 더 최신이다 — 무엇이 먼저 오든, 같은 체결을 양쪽에서 봐도 두 번 세지 않는다.
 * [settled] 는 체결조회가 "남은 수량 0"(`rmn_qty`)으로 답해 이 주문이 끝났다고 확인된 것. 끝난 주문은 다시 조회하지 않는다.
 * 매도는 체결 몫이 보유에 반영될 **그때의 평단** × 수량을 [costBasisAmount] 에 쌓는다 — 부분 매도 사이에 매수가 체결돼 평단이 바뀌어도
 * 매도 손익(체결금액 − 원가)이 맞다(이동평균). 보유가 없던 매도처럼 평단을 모르는 몫은 [costBasisQuantity] 에 안 들어간다.
 * 원장 줄은 내용이 똑같아도 다른 체결이다(같은 초·같은 가격·같은 수량이 따로 체결된다, 2026-10-14 실측) — 원문으로 중복을 거르지 않는다.
 */
@Entity
@Table(name = "order_fill", uniqueConstraints = [UniqueConstraint(columnNames = ["trade_date", "market", "order_no"])])
class OrderFillState(
    /** 주문번호는 날마다 다시 쓰여서 날짜와 함께 써야 한 주문이 된다. 통보·주문을 받은 날(KST). */
    @Column(name = "trade_date", nullable = false)
    val tradeDate: LocalDate,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val market: Market,
    @Column(name = "order_no", nullable = false, length = 40)
    val orderNo: String,
    @Column(nullable = false, length = 20)
    val symbol: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    val side: Side,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @Column(name = "notice_quantity", nullable = false)
    var noticeQuantity: Int = 0
        private set

    @Column(name = "notice_amount", nullable = false, precision = 19, scale = 6)
    var noticeAmount: BigDecimal = BigDecimal.ZERO
        private set

    @Column(name = "inquiry_quantity", nullable = false)
    var inquiryQuantity: Int = 0
        private set

    @Column(name = "inquiry_amount", nullable = false, precision = 19, scale = 6)
    var inquiryAmount: BigDecimal = BigDecimal.ZERO
        private set

    @Column(name = "cost_basis_amount", nullable = false, precision = 19, scale = 6)
    var costBasisAmount: BigDecimal = BigDecimal.ZERO
        private set

    @Column(name = "cost_basis_quantity", nullable = false)
    var costBasisQuantity: Int = 0
        private set

    /** 마지막으로 체결조회 결과를 적은 시각. 조회한 적이 없으면 null. */
    @Column(name = "inquired_at")
    var inquiredAt: Instant? = null
        private set

    @Column(nullable = false)
    var settled: Boolean = false
        private set

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
        private set

    /** 앱이 이 주문의 체결로 보는 값 — 두 출처 중 수량이 큰 쪽(같으면 체결통보). */
    val filled: FilledSoFar
        get() =
            if (inquiryQuantity > noticeQuantity) FilledSoFar(inquiryQuantity, inquiryAmount) else FilledSoFar(noticeQuantity, noticeAmount)

    fun addNotice(
        quantity: Int,
        amount: BigDecimal,
        now: Instant,
    ) {
        noticeQuantity += quantity
        noticeAmount += amount
        updatedAt = now
    }

    fun recordInquiry(
        quantity: Int,
        amount: BigDecimal,
        remainingZero: Boolean,
        now: Instant,
    ) {
        // 조회도 줄어들지 않는다 — 늦게 받은 낡은 응답이 앞선 응답을 덮지 않게 큰 값만 받는다.
        if (quantity >= inquiryQuantity) {
            inquiryQuantity = quantity
            inquiryAmount = amount
        }
        if (remainingZero) settled = true
        inquiredAt = now
        updatedAt = now
    }

    /** 매도 몫 [quantity] 주가 평단 [avgCost] 일 때 보유에서 빠졌다. */
    fun addCostBasis(
        quantity: Int,
        avgCost: BigDecimal,
    ) {
        costBasisAmount += avgCost * BigDecimal(quantity)
        costBasisQuantity += quantity
    }

    /** 매도 손익(체결금액 − 원가). 체결된 몫 전부의 원가를 알 때만, 아니면 null. */
    val realizedPnl: BigDecimal?
        get() {
            val known = side == Side.SELL && filled.quantity > 0 && costBasisQuantity == filled.quantity
            return if (known) filled.amount - costBasisAmount else null
        }

    /** 체결통보만으로 주문수량을 다 채웠으면 조회 없이도 끝난 주문이다. */
    fun settleIfFull(orderQuantity: Int) {
        if (filled.quantity >= orderQuantity) settled = true
    }
}

interface OrderFillStateRepository : JpaRepository<OrderFillState, Long> {
    fun findByTradeDateAndMarketAndOrderNo(
        tradeDate: LocalDate,
        market: Market,
        orderNo: String,
    ): OrderFillState?

    fun findAllByTradeDate(tradeDate: LocalDate): List<OrderFillState>

    fun existsByTradeDate(tradeDate: LocalDate): Boolean
}

/** [OrderFillState] 를 읽는 쪽(대기 해제·잔고 대조·매매 기록·손익)이 같이 쓰는 조회. 쓰는 쪽은 [HoldingFillApplier] 하나다. */
@Component
class OrderFillStates(
    private val repository: OrderFillStateRepository,
) {
    /** 이 주문의 체결(두 출처 중 큰 쪽). 기록이 없으면 0주. */
    fun filled(
        orderNo: String,
        day: LocalDate = OrderFills.today(),
        market: Market = Market.KR,
    ): FilledSoFar = repository.findByTradeDateAndMarketAndOrderNo(day, market, orderNo)?.filled ?: FilledSoFar(0, BigDecimal.ZERO)

    /** 이 주문이 끝났는가 — 주문수량만큼 체결됐거나, 체결조회가 남은 수량 0(취소 포함)으로 확인했다. */
    fun isClosed(
        orderNo: String,
        orderQuantity: Int,
        day: LocalDate = OrderFills.today(),
        market: Market = Market.KR,
    ): Boolean {
        val state = repository.findByTradeDateAndMarketAndOrderNo(day, market, orderNo) ?: return false
        return state.settled || state.filled.quantity >= orderQuantity
    }

    fun settled(
        orderNo: String,
        day: LocalDate,
        market: Market = Market.KR,
    ): Boolean = repository.findByTradeDateAndMarketAndOrderNo(day, market, orderNo)?.settled == true

    fun find(
        orderNo: String,
        day: LocalDate,
        market: Market = Market.KR,
    ): OrderFillState? = repository.findByTradeDateAndMarketAndOrderNo(day, market, orderNo)

    /** 모든 날의 주문별 체결(시장·주문번호·날짜 → 체결 상태). */
    fun allByOrder(): Map<Triple<Market, String, LocalDate>, OrderFillState> =
        repository.findAll().associateBy { Triple(it.market, it.orderNo, it.tradeDate) }

    /** [day] 의 주문별 체결 전부. */
    fun ofDay(day: LocalDate): List<OrderFillState> = repository.findAllByTradeDate(day)
}

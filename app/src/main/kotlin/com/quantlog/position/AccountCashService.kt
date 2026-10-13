package com.quantlog.position

import com.quantlog.broker.Market
import com.quantlog.broker.Side
import com.quantlog.common.KST
import com.quantlog.sync.SyncMismatchReporter
import mu.KotlinLogging
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.MathContext
import java.time.Instant
import java.time.LocalDate

private val log = KotlinLogging.logger {}

/** 지금 이 시장에서 새 매수에 쓸 수 있는 돈. 매수 전 검사([com.quantlog.trading.RiskGuard])가 쓴다. 아직 모르면 null. */
fun interface OrderableCash {
    fun orderable(market: Market): BigDecimal?
}

/**
 * 주문가능금액을 보유 현황과 같은 방식으로 들고 있는다(2026-10-13 사용자 결정): 체결통보가 반영될 때마다 그 체결금액만큼
 * 현금 사본([AccountCash])을 더하고 빼고(매도 체결 → 바로 늘어남, 매수 체결 → 줄어듦), KIS 잔고가 올 때마다 증권사 값으로 바로잡는다.
 * 아직 체결되지 않은 매수 주문은 남은 수량 × 지정가만큼 돈을 잡고 있다고 본다 — 매매 기록에서 그때그때 계산하므로
 * 주문을 내면 줄고, 체결되면 체결가와의 차액이 돌아오고, 취소하면(부분체결 뒤 취소 포함) 안 사진 만큼이 돌아온다.
 * 수수료·세금은 계산하지 않는다 — 잔고 동기화가 맞춘다.
 */
@Service
class AccountCashService(
    private val repository: AccountCashRepository,
    private val tradeRepository: TradeRepository,
    private val events: ApplicationEventPublisher,
) : OrderableCash {
    /** 마지막으로 체결을 현금에 반영한 시각. 그보다 먼저 시작한 잔고 조회 결과로는 덮어쓰지 않는다(체결 이전 값일 수 있다). */
    @Volatile
    private var fillAppliedAt: Instant? = null

    /** 체결 한 줄이 보유 현황에 반영될 때([HoldingFillApplier.applyFill] 과 같은 트랜잭션) 그 체결금액만큼 현금을 고친다. */
    @EventListener
    fun onFillApplied(event: FillAppliedEvent) {
        val row = repository.findByCurrency(event.market.currency) ?: return
        val amount = event.price.multiply(BigDecimal(event.quantity))
        row.update(if (event.side == Side.BUY) row.amount.subtract(amount) else row.amount.add(amount))
        repository.save(row)
        fillAppliedAt = Instant.now()
        events.publishEvent(CashChangedEvent)
    }

    /**
     * [kis] 는 [fetchedAt] 시점에 증권사가 알려 준 현금. 사본을 그 값으로 덮어쓰고, 수수료로 설명되지 않을 만큼 다르면 보고한다.
     * [fetchedAt] 뒤에 체결을 반영했으면 이번 회차는 건너뛴다(다음 회차가 맞춘다).
     */
    @Transactional
    fun sync(
        market: Market,
        kis: BigDecimal,
        fetchedAt: Instant,
    ) {
        if (fillAppliedAt?.isAfter(fetchedAt) == true) return
        val row = repository.findByCurrency(market.currency)
        if (row == null) {
            repository.save(AccountCash(market.currency, kis))
            events.publishEvent(CashChangedEvent)
            log.info { "[현금 동기화] ${market.currency} 처음 받음: ${kis.plain()}" }
            return
        }
        if (row.amount.compareTo(kis) == 0) return
        if (differs(row.amount, kis)) {
            SyncMismatchReporter.report(AREA, market.currency, row.amount.plain(), kis.plain(), "DB 를 증권사 값으로 고침")
        }
        row.update(kis)
        events.publishEvent(CashChangedEvent)
    }

    /** 현금 사본에서 아직 체결되지 않은 오늘의 매수 주문이 잡고 있는 돈을 뺀 값. 현금을 아직 한 번도 못 받았으면 null. */
    override fun orderable(market: Market): BigDecimal? {
        val cash = repository.findByCurrency(market.currency)?.amount ?: return null
        return cash.subtract(reservedByOpenBuys(market))
    }

    private fun reservedByOpenBuys(market: Market): BigDecimal {
        val startOfToday = LocalDate.now(KST).atStartOfDay(KST).toInstant()
        return tradeRepository.findAllByMarketAndSideAndCanceledFalseAndExecutedAtGreaterThanEqual(market, Side.BUY, startOfToday)
            .fold(BigDecimal.ZERO) { sum, trade -> sum + trade.orderPrice * BigDecimal(trade.unfilledQuantity) }
    }

    /** 수수료·세금(체결금액의 0.0x%)은 우리가 계산하지 않아 조금씩 어긋난다. 그보다 크게 다를 때만 "어긋났다"고 본다. */
    private fun differs(
        db: BigDecimal,
        kis: BigDecimal,
    ): Boolean {
        val base = db.abs().max(kis.abs())
        if (base.signum() == 0) return false
        return db.subtract(kis).abs().divide(base, MathContext.DECIMAL64) > TOLERANCE
    }

    private companion object {
        val TOLERANCE: BigDecimal = BigDecimal("0.001")
        const val AREA = "현금 동기화"
    }
}

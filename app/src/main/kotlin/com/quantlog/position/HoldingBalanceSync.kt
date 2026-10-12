package com.quantlog.position

import com.quantlog.broker.Holding
import com.quantlog.broker.Market
import com.quantlog.sync.SyncMismatchReporter
import com.quantlog.watchlist.SymbolStrategyService
import mu.KotlinLogging
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.MathContext
import java.time.Instant

private val log = KotlinLogging.logger {}

/**
 * 게이트웨이의 잔고 스냅샷(증권사 값)으로 보유 현황([AccountHolding])을 덮어쓴다(증권사가 정답).
 * 어긋난 곳은 `[동기화 불일치]` 로 보고한다. 체결 통보로 그때그때 고치는 일은 [HoldingFillApplier] 가 한다.
 */
@Service
class HoldingBalanceSync(
    private val accountHoldingRepository: AccountHoldingRepository,
    private val symbolNames: SymbolStrategyService,
    private val events: ApplicationEventPublisher,
    private val state: HoldingUpdateTimes,
) {
    /**
     * [kis] 는 [fetchedAt] 시점에 받은 잔고 전체. 바뀐 내용 설명 목록을 돌려준다(변화가 없으면 빈 목록).
     * [fetchedAt] 은 KIS 를 부르기 **전** 시각이어야 한다 — 조회 도중 낸 주문을 "반영됨"으로 착각하지 않기 위해서다.
     */
    @Transactional
    fun sync(
        kis: List<Holding>,
        fetchedAt: Instant = Instant.now(),
    ): List<String> {
        val actual = kis.filter { it.quantity.toInt() > 0 }.associateBy { it.market to it.symbol }
        val existing = accountHoldingRepository.findAll().associateBy { it.market to it.symbol }
        val changes = mutableListOf<String>()
        // 수량이 그대로여도 현재가가 바뀌면 화면(평가손익)이 갱신돼야 한다.
        var priceChanged = false

        // 이 조회를 시작한 뒤에 체결을 반영한 종목은 조회 결과가 체결 이전 값일 수 있어 이번 회차엔 건드리지 않는다(다음 회차가 맞춘다).
        fun reflectedAfterFetch(key: Pair<Market, String>) = state.fillAppliedAfter(key, fetchedAt)

        actual.filterKeys { !reflectedAfterFetch(it) }.forEach { (key, holding) ->
            val row = existing[key]
            if (row == null) {
                changes += addMissing(holding)
            } else {
                changes += listOfNotNull(correct(row, holding))
                if (row.currentPrice.compareTo(holding.currentPrice) != 0) priceChanged = true
                row.update(holding.quantity.toInt(), holding.averagePrice, holding.currentPrice)
            }
        }
        existing.filterKeys { it !in actual && !reflectedAfterFetch(it) }.values.forEach { changes += removeGone(it) }

        state.lastSyncedAt = fetchedAt
        changes.forEach { log.info { "[잔고 동기화] $it" } }
        if (changes.isNotEmpty() || priceChanged) events.publishEvent(HoldingsChangedEvent)
        return changes
    }

    private fun label(
        market: Market,
        symbol: String,
    ) = "${symbolNames.displayName(market, symbol)}($symbol)"

    private fun addMissing(holding: Holding): String {
        val quantity = holding.quantity.toInt()
        accountHoldingRepository.save(AccountHolding(holding.market, holding.symbol, quantity, holding.averagePrice, holding.currentPrice))
        SyncMismatchReporter.report(
            AREA,
            label(holding.market, holding.symbol),
            "보유 없음",
            "${quantity}주 (평단 ${holding.averagePrice.plain()})",
            "DB 에 추가",
        )
        return "${symbolNames.displayName(holding.market, holding.symbol)} 신규 ${quantity}주 (평단 ${holding.averagePrice})"
    }

    /** 수량이 다르면 바뀐 내용을 돌려주고, 평단만 다르면 보고만 한다. 값을 덮어쓰는 건 호출한 쪽이다. */
    private fun correct(
        row: AccountHolding,
        holding: Holding,
    ): String? {
        val quantity = holding.quantity.toInt()
        val subject = label(row.market, row.symbol)
        if (row.quantity != quantity) {
            SyncMismatchReporter.report(
                AREA,
                subject,
                "${row.quantity}주 (평단 ${row.avgCost.plain()})",
                "${quantity}주 (평단 ${holding.averagePrice.plain()})",
                "DB 를 증권사 값으로 고침",
            )
            return "${symbolNames.displayName(row.market, row.symbol)} ${row.quantity}주 → ${quantity}주 (평단 ${holding.averagePrice})"
        }
        if (averageDiffers(row.avgCost, holding.averagePrice)) {
            SyncMismatchReporter.report(
                AREA,
                subject,
                "평단 ${row.avgCost.plain()} (${quantity}주)",
                "평단 ${holding.averagePrice.plain()} (${quantity}주)",
                "DB 평단을 증권사 값으로 고침",
            )
        }
        return null
    }

    private fun removeGone(row: AccountHolding): String {
        accountHoldingRepository.delete(row)
        SyncMismatchReporter.report(AREA, label(row.market, row.symbol), "${row.quantity}주", "보유 없음", "DB 에서 삭제")
        return "${symbolNames.displayName(row.market, row.symbol)} 잔고에서 사라짐 (${row.quantity}주)"
    }

    /**
     * 평단이 "어긋났다"고 볼 기준: 두 값의 차이가 큰 쪽의 0.01% 를 넘을 때. 체결가들로 직접 계산한 평단과 증권사가 주는 평단은
     * 소수 자릿수·반올림이 달라 미세하게 다를 수 있어서, 그 정도로는 매번 오류를 내지 않는다.
     */
    private fun averageDiffers(
        db: BigDecimal,
        kis: BigDecimal,
    ): Boolean {
        val base = db.abs().max(kis.abs())
        if (base.signum() == 0) return false
        return db.subtract(kis).abs().divide(base, MathContext.DECIMAL64) > AVERAGE_TOLERANCE
    }

    private companion object {
        val AVERAGE_TOLERANCE: BigDecimal = BigDecimal("0.0001")
        const val AREA = "잔고 동기화"
    }
}

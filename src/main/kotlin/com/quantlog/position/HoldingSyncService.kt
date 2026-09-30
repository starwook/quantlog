package com.quantlog.position

import com.quantlog.broker.Holding
import com.quantlog.broker.Market
import com.quantlog.watchlist.displayNameOf
import mu.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

private val log = KotlinLogging.logger {}

/**
 * KIS 잔고를 [AccountHolding] 테이블에 그대로 맞춘다 (KIS 가 정답, DB 는 사본). 새 종목은 추가, 있는 종목은 수량·평단·현재가 갱신,
 * KIS 잔고에서 사라진 종목은 삭제한다. 호출은 [com.quantlog.trading.HoldingSyncScheduler] 가 주기적으로 한다.
 */
@Service
class HoldingSyncService(
    private val accountHoldingRepository: AccountHoldingRepository,
    private val tradeRepository: TradeRepository,
) {
    /** 마지막으로 잔고를 받기 시작한 시각. 한 번도 못 받았으면 null. */
    @Volatile
    private var lastSyncedAt: Instant? = null

    /**
     * 이 종목에 잔고 동기화보다 늦게 낸 주문이 있으면 true — 잔고 테이블이 그 주문을 아직 반영하지 못한 상태라
     * 스케줄러는 이 종목 판단(추가 매수·청산)을 다음 동기화까지 미룬다. 첫 동기화 전에도 true.
     */
    fun hasUnsyncedTrade(
        market: Market,
        symbol: String,
    ): Boolean {
        val synced = lastSyncedAt ?: return true
        val last = tradeRepository.findFirstByMarketAndSymbolOrderByExecutedAtDesc(market, symbol) ?: return false
        return last.executedAt.isAfter(synced)
    }

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

        actual.forEach { (key, holding) ->
            val quantity = holding.quantity.toInt()
            val row = existing[key]
            if (row == null) {
                accountHoldingRepository.save(
                    AccountHolding(holding.market, holding.symbol, quantity, holding.averagePrice, holding.currentPrice),
                )
                changes += "${displayNameOf(holding.market, holding.symbol)} 신규 ${quantity}주 (평단 ${holding.averagePrice})"
            } else {
                if (row.quantity != quantity) {
                    changes += "${displayNameOf(row.market, row.symbol)} ${row.quantity}주 → ${quantity}주 (평단 ${holding.averagePrice})"
                }
                row.update(quantity, holding.averagePrice, holding.currentPrice)
            }
        }
        existing.filterKeys { it !in actual }.values.forEach {
            accountHoldingRepository.delete(it)
            changes += "${displayNameOf(it.market, it.symbol)} 잔고에서 사라짐 (${it.quantity}주)"
        }

        lastSyncedAt = fetchedAt
        changes.forEach { log.info { "[잔고 동기화] $it" } }
        return changes
    }
}

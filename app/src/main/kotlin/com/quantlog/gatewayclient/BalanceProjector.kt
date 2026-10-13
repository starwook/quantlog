package com.quantlog.gatewayclient

import com.fasterxml.jackson.databind.ObjectMapper
import com.quantlog.broker.KisBalanceParser
import com.quantlog.broker.Market
import com.quantlog.position.AccountCashService
import com.quantlog.position.HoldingBalanceSync
import mu.KotlinLogging
import org.springframework.stereotype.Component

private val log = KotlinLogging.logger {}

/**
 * 게이트웨이가 원문 그대로 쌓은 잔고(`kis_balance`)의 가장 최근 줄이 새 것이면 한투 문서대로 읽어([KisBalanceParser]) 보유 현황과 현금 사본에 맞춘다.
 * 앱이 꺼져 있던 동안 쌓인 줄은 중간 것을 건너뛰어도 된다(각 줄이 그 시점의 전체 잔고). 읽을 수 없는 줄은 ERROR 로 알리고 건너뛴다.
 * 맞춘 뒤 보유 종목을 `held_symbol` 로 알려 게이트웨이가 그 종목의 실시간 시세를 먼저 구독하게 한다.
 */
@Component
class BalanceProjector(
    private val balances: KisBalanceRowRepository,
    private val cursors: ProjectionCursorRepository,
    private val holdingSync: HoldingBalanceSync,
    private val accountCash: AccountCashService,
    private val heldSymbols: HeldSymbolPublisher,
    private val objectMapper: ObjectMapper,
) {
    /** 반영했으면 true. */
    @Synchronized
    fun project(): Boolean {
        val row = balances.findTopByOrderByIdDesc() ?: return false
        val applied = cursors.findById(CURSOR).map { it.lastId }.orElse(0)
        if (row.id!! <= applied) return false
        val (holdings, cash) =
            runCatching { objectMapper.readTree(row.body).let { KisBalanceParser.parse(it) to KisBalanceParser.cash(it) } }.getOrElse {
                log.error(it) { "[잔고 해석 실패] 건너뛴다(다음 회차가 맞춘다): kis_balance.id=${row.id} ${row.body.take(300)}" }
                cursors.save(ProjectionCursor(CURSOR, row.id!!))
                return false
            }
        holdingSync.sync(holdings, row.fetchedAt)
        cash?.let { accountCash.sync(Market.KR, it, row.fetchedAt) }
        heldSymbols.publish(holdings)
        cursors.save(ProjectionCursor(CURSOR, row.id!!))
        return true
    }

    private companion object {
        /** 옛 `broker_balance_meta` 회차 번호용 커서 "balance" 와 섞이지 않게 이름을 바꿨다. */
        const val CURSOR = "kis_balance"
    }
}

package com.quantlog.gatewayclient

import com.quantlog.broker.FillNotice
import com.quantlog.broker.Holding
import com.quantlog.broker.Market
import com.quantlog.position.HoldingSyncService
import com.quantlog.position.TradeService
import mu.KotlinLogging
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

private val log = KotlinLogging.logger {}

/**
 * 게이트웨이가 기록한 체결 원장(`broker_fill`)을 앱의 보유 현황·매매 기록에 반영한다. 어디까지 처리했는지는 `broker_projection_cursor` 에 둔다 —
 * 앱이 꺼져 있던 동안 쌓인 체결도 재시작 후 이어서 처리한다(적어도 한 번 처리; 같은 통보 중복은 [HoldingSyncService.applyFill] 이 막는다).
 * 커서가 없으면(처음 켠 때) 지금 있는 마지막 ID 부터 시작한다 — 그 이전 체결은 잔고 스냅샷과 체결 보충이 맞춘다.
 *
 * 동기화 불일치 보고 불필요: 증권사와 DB 를 비교하는 게 아니라 원장 행을 옮겨 적는 일이다. 불일치 보고는 [HoldingSyncService] 가 한다.
 */
@Component
class FillProjector(
    private val fills: BrokerFillRowRepository,
    private val cursors: ProjectionCursorRepository,
    private val holdingSync: HoldingSyncService,
    private val tradeService: TradeService,
    transactionManager: PlatformTransactionManager,
) {
    private val tx = TransactionTemplate(transactionManager)

    /** 처리한 행 수를 돌려준다. */
    @Synchronized
    fun project(): Int {
        var last = cursors.findById(CURSOR).map { it.lastId }.orElse(null)
        if (last == null) {
            last = fills.maxId() ?: 0
            cursors.save(ProjectionCursor(CURSOR, last))
            log.info { "[체결 반영] 커서 초기화: $last" }
            return 0
        }
        var processed = 0
        while (true) {
            val rows = fills.findTop100ByIdGreaterThanOrderByIdAsc(last!!)
            if (rows.isEmpty()) return processed
            rows.forEach { row ->
                val notice = row.toNotice()
                // 한 행이 실패해도 다음 행을 막지 않는다 — 틀린 부분은 다음 잔고 스냅샷 반영이 바로잡는다.
                runCatching {
                    tx.executeWithoutResult {
                        holdingSync.applyFill(notice)
                        tradeService.onFillNotice(notice)
                        cursors.save(ProjectionCursor(CURSOR, row.id!!))
                    }
                }.onFailure {
                    // 건너뛴 체결을 나중에 찾아볼 수 있게 ERROR 로 남긴다(/errors·웹훅). 원장 행 ID(broker_fill.id)와 주문번호로 그 행을 특정한다.
                    log.error(it) { "[체결 반영 실패] 건너뛴다(다음 잔고 반영이 보유를 바로잡는다): broker_fill.id=${row.id} ${notice.summary()}" }
                    cursors.save(ProjectionCursor(CURSOR, row.id!!))
                }
                last = row.id
                processed++
            }
        }
    }

    private fun BrokerFillRow.toNotice() =
        FillNotice(
            symbol = symbol,
            orderNo = orderNo,
            originalOrderNo = originalOrderNo ?: "",
            sellBuyCode = sellBuyCode,
            filledFlag = filledFlag,
            acceptFlag = acceptFlag ?: "",
            refuseFlag = refuseFlag ?: "",
            filledQuantity = filledQuantity,
            filledPrice = filledPrice,
            orderQuantity = orderQuantity,
            orderPrice = orderPrice,
            time = noticeTime,
        )

    private companion object {
        const val CURSOR = "fill"
    }
}

/** 게이트웨이의 잔고 스냅샷(`broker_balance`+meta)이 새 회차(seq)면 보유 현황에 맞춘다. 스냅샷은 한 트랜잭션으로 쓰이므로 한 트랜잭션으로 읽는다. */
@Component
class BalanceProjector(
    private val balances: BrokerBalanceRowRepository,
    private val metas: BrokerBalanceMetaRowRepository,
    private val cursors: ProjectionCursorRepository,
    private val holdingSync: HoldingSyncService,
    transactionManager: PlatformTransactionManager,
) {
    private val readTx = TransactionTemplate(transactionManager).apply { isReadOnly = true }

    /** 반영했으면 true. */
    @Synchronized
    fun project(): Boolean {
        val snapshot =
            readTx.execute {
                val meta = metas.findById(1L).orElse(null) ?: return@execute null
                meta to balances.findAll()
            } ?: return false
        val (meta, rows) = snapshot
        val applied = cursors.findById(CURSOR).map { it.lastId }.orElse(0)
        if (meta.seq <= applied) return false
        val holdings =
            rows.map {
                Holding(Market.valueOf(it.market), it.symbol, it.name, it.quantity, it.averagePrice, it.currentPrice)
            }
        holdingSync.sync(holdings, meta.fetchedAt)
        cursors.save(ProjectionCursor(CURSOR, meta.seq))
        return true
    }

    private companion object {
        const val CURSOR = "balance"
    }
}

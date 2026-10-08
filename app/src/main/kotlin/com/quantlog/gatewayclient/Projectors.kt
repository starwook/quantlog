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

    /** 행 ID 별 연속 실패 횟수. */
    private val failures = mutableMapOf<Long, Int>()

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
            for (row in rows) {
                val notice = row.toNotice()
                val failure =
                    runCatching {
                        tx.executeWithoutResult {
                            holdingSync.applyFill(notice)
                            tradeService.onFillNotice(notice)
                            cursors.save(ProjectionCursor(CURSOR, row.id!!))
                        }
                    }.exceptionOrNull()
                if (failure != null) {
                    // 원장은 유실이 없으니 실패한 행을 건너뛰지 않는다 — 커서를 그 행 앞에 둔 채 멈추고 다음 회차에 같은 행부터 다시 한다(대개 DB 일시 오류).
                    // 같은 행이 계속 실패하면(데이터 문제 등) 뒤 체결이 영영 막히지 않게 마지막에 한 번 크게 알리고 건너뛴다.
                    val attempts = failures.merge(row.id!!, 1, Int::plus)!!
                    if (attempts < MAX_ATTEMPTS) {
                        log.warn(failure) { "[체결 반영] 실패 — 다음 회차에 이 행부터 다시 한다($attempts/$MAX_ATTEMPTS): id=${row.id} ${notice.summary()}" }
                        return processed
                    }
                    log.error(
                        failure,
                    ) { "[체결 반영] ${MAX_ATTEMPTS}번 실패해 건너뛴다 — 보유·체결 기록을 확인할 것(다음 잔고 반영이 보유는 바로잡는다): id=${row.id} ${notice.summary()}" }
                    cursors.save(ProjectionCursor(CURSOR, row.id!!))
                }
                failures.remove(row.id!!)
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
        const val MAX_ATTEMPTS = 30
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

package com.quantlog.gatewayclient

import com.quantlog.broker.FillNotice
import com.quantlog.broker.Holding
import com.quantlog.broker.Market
import com.quantlog.broker.Side
import com.quantlog.position.HoldingSyncService
import com.quantlog.position.OrderFill
import com.quantlog.position.OrderFills
import com.quantlog.position.TradeService
import mu.KotlinLogging
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

private val log = KotlinLogging.logger {}

/** [FillProjector] 가 체결 원장을 어디까지 반영했는지 적는 커서 이름(`broker_projection_cursor.name`). */
private const val FILL_CURSOR = "fill"

/**
 * 게이트웨이가 기록한 체결 원장(`broker_fill`)을 앱의 보유 현황(`account_holding`)·매매 기록(`trade`)에 반영한다. 어디까지 처리했는지는
 * `broker_projection_cursor` 에 둔다 — 앱이 꺼져 있던 동안 쌓인 체결도 재시작 후 그다음 줄부터 이어서 처리한다(같은 통보 중복은
 * [HoldingSyncService.applyFill] 이 막는다). 체결 내역 자체는 원장에만 있고 앱은 사본을 따로 쓰지 않는다([OrderFills]).
 * 커서가 없으면(처음 켠 때) 지금 있는 마지막 ID 부터 시작한다 — 그 이전 체결은 잔고 스냅샷이 맞춘다.
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
                        // 체결은 보유 현황에 반영하고(매매 기록은 그 반영 이벤트로 따라온다), 접수 통보는 "증권사가 받은 미체결 주문"으로 표시한다.
                        if (notice.isFill) holdingSync.applyFill(notice, row.id!!, row.receivedAt) else tradeService.onOrderNotice(notice)
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
        const val CURSOR = FILL_CURSOR
    }
}

/** [OrderFills] 를 게이트웨이 원장(`broker_fill`)에서 읽는다. "반영된" 줄은 [FillProjector] 의 커서까지다. */
@Component
class BrokerFillOrderFills(
    private val fills: BrokerFillRowRepository,
    private val cursors: ProjectionCursorRepository,
) : OrderFills {
    override fun upTo(
        orderNo: String,
        lastId: Long,
    ): List<OrderFill> = fills.findAllByOrderNoAndIdLessThanEqualOrderByIdAsc(orderNo, lastId).mapNotNull { it.toFill() }

    override fun projected(orderNo: String): List<OrderFill> = upTo(orderNo, projectedUpTo())

    override fun projectedAll(): List<OrderFill> = fills.findAllByIdLessThanEqualOrderByIdAsc(projectedUpTo()).mapNotNull { it.toFill() }

    override fun accepted(orderNo: String): Boolean =
        fills.findAllByOrderNoAndIdLessThanEqualOrderByIdAsc(orderNo, projectedUpTo()).any {
            it.filledFlag != FillNotice.FILLED_FLAG && (it.refuseFlag.isNullOrBlank() || it.refuseFlag == NOT_REFUSED)
        }

    /** 지금까지 반영을 끝낸 마지막 원장 ID. 커서가 아직 없으면 0. */
    private fun projectedUpTo(): Long = cursors.findById(FILL_CURSOR).map { it.lastId }.orElse(0)

    /** 체결 통보 줄만 [OrderFill] 로 바꾼다. 접수 통보이거나 값이 빠진 줄은 null. */
    private fun BrokerFillRow.toFill(): OrderFill? {
        if (filledFlag != FillNotice.FILLED_FLAG) return null
        val side =
            when (sellBuyCode) {
                "01" -> Side.SELL
                "02" -> Side.BUY
                else -> return null
            }
        val quantity = filledQuantity?.toInt()?.takeIf { it > 0 } ?: return null
        val price = filledPrice ?: return null
        return OrderFill(id!!, Market.KR, symbol, side, orderNo, quantity, price, receivedAt)
    }

    private companion object {
        /** RFUS_YN: 정상 접수면 "0". */
        const val NOT_REFUSED = "0"
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

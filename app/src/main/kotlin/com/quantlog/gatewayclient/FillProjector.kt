package com.quantlog.gatewayclient

import com.quantlog.broker.KisFillNoticeParser
import com.quantlog.position.HoldingBalanceSync
import com.quantlog.position.HoldingFillApplier
import com.quantlog.position.OrderFills
import com.quantlog.position.TradeService
import mu.KotlinLogging
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

private val log = KotlinLogging.logger {}

/** [FillProjector] 가 체결통보 원장(`kis_broker_fill`)을 어디까지 반영했는지 적는 커서 이름(`broker_projection_cursor.name`). 옛 `broker_fill` 용 커서 "fill" 과 섞이지 않게 이름을 바꿨다. */
internal const val FILL_CURSOR = "kis_broker_fill"

/**
 * 게이트웨이가 원문 그대로 기록한 체결통보 원장(`kis_broker_fill`)을 한투 문서대로 읽어([KisFillNoticeParser]) 앱의 보유 현황(`account_holding`)·
 * 매매 기록(`trade`)에 반영한다. 어디까지 처리했는지는 `broker_projection_cursor` 에 둔다 — 앱이 꺼져 있던 동안 쌓인 통보도 재시작 후 그다음 줄부터
 * 이어서 처리한다(같은 통보 중복은 [HoldingFillApplier.applyFill] 이 막는다). 체결 내역 자체는 원장에만 있고 앱은 사본을 따로 쓰지 않는다([OrderFills]).
 * 커서가 없으면(처음 켠 때) 지금 있는 마지막 ID 부터 시작한다 — 그 이전 체결은 잔고 스냅샷이 맞춘다.
 *
 * 동기화 불일치 보고 불필요: 증권사와 DB 를 비교하는 게 아니라 원장 행을 옮겨 적는 일이다. 불일치 보고는 [HoldingBalanceSync] 가 한다.
 */
@Component
class FillProjector(
    private val notices: KisBrokerFillRowRepository,
    private val cursors: ProjectionCursorRepository,
    private val holdingSync: HoldingFillApplier,
    private val tradeService: TradeService,
    transactionManager: PlatformTransactionManager,
) {
    private val tx = TransactionTemplate(transactionManager)

    /** 처리한 행 수를 돌려준다. */
    @Synchronized
    fun project(): Int {
        var last = cursors.findById(CURSOR).map { it.lastId }.orElse(null)
        if (last == null) {
            last = notices.maxId() ?: 0
            cursors.save(ProjectionCursor(CURSOR, last))
            log.info { "[체결 반영] 커서 초기화: $last" }
            return 0
        }
        var processed = 0
        while (true) {
            val rows = notices.findTop100ByIdGreaterThanOrderByIdAsc(last!!)
            if (rows.isEmpty()) return processed
            rows.forEach { row ->
                project(row)
                last = row.id
                processed++
            }
        }
    }

    private fun project(row: KisBrokerFillRow) {
        val notice = KisFillNoticeParser.parse(row.trId, row.body)
        if (notice == null) {
            log.error { "[체결통보 해석 실패] 건너뛴다(다음 잔고 반영이 보유를 바로잡는다): kis_broker_fill.id=${row.id} ${row.trId} ${row.body}" }
            cursors.save(ProjectionCursor(CURSOR, row.id!!))
            return
        }
        // 한 행이 실패해도 다음 행을 막지 않는다 — 틀린 부분은 다음 잔고 스냅샷 반영이 바로잡는다.
        runCatching {
            tx.executeWithoutResult {
                // 체결은 보유 현황에 반영하고(매매 기록은 그 반영 이벤트로 따라온다), 접수 통보는 "증권사가 받은 미체결 주문"으로 표시한다.
                if (notice.isFill) holdingSync.applyFill(notice, row.id!!, row.receivedAt) else tradeService.onOrderNotice(notice)
                cursors.save(ProjectionCursor(CURSOR, row.id!!))
            }
        }.onFailure {
            // 건너뛴 체결을 나중에 찾아볼 수 있게 ERROR 로 남긴다(/errors·웹훅). 원장 행 ID(kis_broker_fill.id)와 주문번호로 그 행을 특정한다.
            log.error(it) { "[체결 반영 실패] 건너뛴다(다음 잔고 반영이 보유를 바로잡는다): kis_broker_fill.id=${row.id} ${notice.summary()}" }
            cursors.save(ProjectionCursor(CURSOR, row.id!!))
        }
    }

    private companion object {
        const val CURSOR = FILL_CURSOR
    }
}

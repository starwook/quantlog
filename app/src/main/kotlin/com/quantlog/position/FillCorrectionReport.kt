package com.quantlog.position

import com.quantlog.broker.OrderStatus
import com.quantlog.sync.SyncMismatchReporter
import java.time.Duration
import java.time.Instant

/** 주문 직후엔 체결통보·체결가 조회가 DB 에 닿기까지 몇 초 걸리는 게 정상이라, 이 시간이 지난 주문만 불일치로 본다. */
private val FILL_REPORT_GRACE: Duration = Duration.ofSeconds(30)

/**
 * 증권사 체결 조회가 "체결"인데 DB 에는 체결 정보가 없거나 일부뿐이어서 DB 를 고쳤을 때, 그 사실을 동기화 불일치로 보고한다.
 * [dbBefore] 는 고치기 **전** DB 상태([Trade.fillStateText]). 체결 조회로 DB 를 고치는 곳(TradeService.applyStatus, TradeReconciler)이 함께 쓴다.
 */
fun reportFillCorrection(
    trade: Trade,
    dbBefore: String,
    status: OrderStatus.Filled,
    now: Instant = Instant.now(),
) {
    if (Duration.between(trade.executedAt, now) < FILL_REPORT_GRACE) return
    SyncMismatchReporter.report(
        "체결 조회",
        "${trade.market} ${trade.symbol} 주문번호=${trade.orderNo}",
        dbBefore,
        "전체 체결 ${status.price.stripTrailingZeros().toPlainString()}",
        "DB 체결 정보를 증권사 값으로 채움",
    )
}

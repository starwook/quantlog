package com.quantlog.position

import org.springframework.stereotype.Component

/**
 * 보유 현황(`account_holding`)·주문별 체결 합계(`order_fill`)를 고치는 일을 한 줄로 세운다. 체결통보 반영·잔고 스냅샷 반영
 * ([com.quantlog.trading.HoldingSyncScheduler])과 체결조회 반영([FillReconcileService])이 이 잠금 안에서만 고친다 — DB 에 행 잠금·버전이 없어서,
 * 서로 다른 스레드가 같은 행을 읽고 고치면 한쪽 변경이 사라진다. 한투 호출처럼 오래 걸리는 일은 잠금 밖에서 한다.
 */
@Component
class HoldingWriteLock {
    fun <T> run(block: () -> T): T = synchronized(this) { block() }
}

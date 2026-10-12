package com.quantlog.position

import com.quantlog.broker.Market
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * 보유 현황을 고치는 두 경로([HoldingFillApplier] 체결 반영, [HoldingBalanceSync] 잔고 동기화)와 그걸 읽는 쪽([FillProgressTracker])이
 * 같이 보는 "언제 무엇을 반영했나" 기록.
 */
@Component
class HoldingUpdateTimes {
    /** 마지막으로 잔고를 받기 시작한 시각. 한 번도 못 받았으면 null. */
    @Volatile
    var lastSyncedAt: Instant? = null

    /** 종목별로 체결을 마지막으로 반영한 시각. 그보다 먼저 시작한 잔고 조회 결과로는 그 종목을 덮어쓰지 않는다. */
    private val fillAppliedAt = ConcurrentHashMap<Pair<Market, String>, Instant>()

    fun markFillApplied(
        key: Pair<Market, String>,
        at: Instant,
    ) {
        fillAppliedAt[key] = at
    }

    /** 이 종목의 체결이 [fetchedAt] 이후에 반영됐는가 — 그렇다면 [fetchedAt] 에 시작한 잔고 조회 결과는 체결 이전 값일 수 있다. */
    fun fillAppliedAfter(
        key: Pair<Market, String>,
        fetchedAt: Instant,
    ): Boolean = fillAppliedAt[key]?.isAfter(fetchedAt) == true
}

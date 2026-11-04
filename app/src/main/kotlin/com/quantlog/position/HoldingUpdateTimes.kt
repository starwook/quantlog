package com.quantlog.position

import com.quantlog.broker.Market
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * 잔고 덮어쓰기([HoldingBalanceSync])가 "체결통보를 기다리는 주문"으로 보는 범위. 이보다 오래된 주문 때문에 보유 대조가 막히지 않게 한다(원칙집 46행).
 * 매수 판단의 주문 대기([FillProgressTracker.hasUnfinishedOrder])는 이 시간을 쓰지 않는다 — 끝났다고 확인될 때만 풀린다(2026-11-04).
 */
internal val ORDER_IN_FLIGHT_LIMIT: Duration = Duration.ofMinutes(3)

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

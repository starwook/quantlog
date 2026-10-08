package com.quantlog.trading

import com.quantlog.position.FillBackfillService
import mu.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

private val log = KotlinLogging.logger {}

/**
 * 체결통보를 놓쳤을 때(서버 재시작·연결 끊김) 빠진 체결을 주기적으로 원장에 채운다([FillBackfillService]). 서버가 뜬 지 20초 뒤에
 * 첫 점검이 돌아 재시작 때 놓친 체결을 바로 메우고, 이후 3분마다 돈다. 주문을 내지 않는 조회·기록뿐이라 자동매매 스위치와 무관하게 돈다.
 */
@Component
class FillBackfillScheduler(private val service: FillBackfillService) {
    @Scheduled(fixedDelay = 180_000, initialDelay = 20_000)
    fun run() {
        runCatching { service.backfill() }.onFailure { log.warn(it) { "[체결 보충] 점검 실패: ${it.message}" } }
    }
}

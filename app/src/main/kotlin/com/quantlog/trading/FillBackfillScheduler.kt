package com.quantlog.trading

import com.quantlog.position.FillBackfillService
import mu.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

private val log = KotlinLogging.logger {}

/**
 * 게이트웨이 원장이 체결통보를 놓쳤는지 주기적으로 KIS 와 대조한다([FillBackfillService]). 앱이 뜬 지 20초 뒤에 첫 점검, 이후 3분마다.
 * 게이트웨이의 증권사 웹소켓이 끊겼다는 신호 없이 조용히 죽으면 재연결 감지([com.quantlog.gatewayclient.GatewayWatcher])가 못 잡으므로
 * 이 주기 점검이 마지막 안전망이다. 주문을 내지 않는 조회뿐이라 자동매매 스위치와 무관하게 돈다.
 */
@Component
class FillBackfillScheduler(private val service: FillBackfillService) {
    @Scheduled(fixedDelay = 180_000, initialDelay = 20_000)
    fun run() {
        runCatching { service.backfill() }.onFailure { log.warn(it) { "[체결 대조] 점검 실패: ${it.message}" } }
    }
}

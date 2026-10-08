package com.quantlog.gateway.api

import com.quantlog.gateway.kis.KisRealtimeClient
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 앱이 감시 종목(`watch_symbol`)을 바꾼 직후 부르는 신호. DB 를 다시 읽어 실시간 구독을 지금 맞춘다.
 * 이 호출이 실패해도 [KisRealtimeClient] 가 주기적으로 같은 일을 하므로 늦어질 뿐 틀어지지는 않는다.
 */
@RestController
@RequestMapping("/api/watch")
class WatchApi(
    private val realtime: KisRealtimeClient,
) {
    @PostMapping("/refresh")
    fun refresh(): Map<String, Any> {
        realtime.refreshSubscriptions()
        return mapOf("liveSymbols" to realtime.liveSymbols())
    }
}

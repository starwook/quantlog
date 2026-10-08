package com.quantlog.gateway.lease

import com.quantlog.gateway.kis.KisProperties
import com.quantlog.gateway.kis.KisRealtimeClient
import mu.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import kotlin.system.exitProcess

private val log = KotlinLogging.logger {}

/** 주기적으로 하트비트를 쓰고 잠금을 늘린다. 잠금을 잃으면(다른 인스턴스가 이어받음) 증권사 연결이 겹치지 않게 스스로 끝낸다. */
@Component
class Heartbeat(
    private val lease: LeaseService,
    private val realtime: KisRealtimeClient,
) {
    @Scheduled(fixedDelayString = "\${quantlog.gateway.heartbeat-millis:5000}")
    fun beat() {
        val ok =
            runCatching { lease.renew(realtime.wsConnectedAt(), realtime.liveSymbols()) }.getOrElse {
                log.warn(it) { "[게이트웨이] 하트비트를 못 썼다(DB 오류) — 다음 주기에 다시 시도" }
                return
            }
        if (!ok) {
            log.error { "[게이트웨이] 단일 실행 잠금을 잃었다(다른 인스턴스가 이어받음) — 증권사 연결이 겹치지 않게 종료한다" }
            exitProcess(1)
        }
    }
}

/** 앱·운영자가 보는 상태. */
data class HealthView(
    val instanceId: String,
    val startedAt: Instant,
    val contractVersion: Int,
    val kisCredentials: Boolean,
    val wsConnected: Boolean,
    val wsConnectedAt: Instant?,
    val liveSymbols: List<String>,
    val now: Instant,
)

@RestController
@RequestMapping("/api")
class HealthApi(
    private val lease: LeaseService,
    private val realtime: KisRealtimeClient,
    private val kis: KisProperties,
) {
    @GetMapping("/health")
    fun health(): HealthView =
        HealthView(
            instanceId = lease.instanceId,
            startedAt = lease.startedAt,
            contractVersion = CONTRACT_VERSION,
            kisCredentials = kis.hasCredentials,
            wsConnected = realtime.wsConnectedAt() != null,
            wsConnectedAt = realtime.wsConnectedAt(),
            liveSymbols = realtime.liveSymbols(),
            now = Instant.now(),
        )
}

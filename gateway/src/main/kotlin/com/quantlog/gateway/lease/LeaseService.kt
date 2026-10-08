package com.quantlog.gateway.lease

import com.quantlog.gateway.GatewayProperties
import jakarta.annotation.PostConstruct
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.util.UUID

private val log = KotlinLogging.logger {}

/** 앱과 맞추는 계약 버전. 계약은 추가만 하므로 올릴 일이 드물다 — 앱은 이 값이 필요한 값보다 낮으면 매매를 멈춘다. */
const val CONTRACT_VERSION = 2

/** 이미 다른 게이트웨이 인스턴스가 살아 있다. */
class GatewayAlreadyRunningException(message: String) : IllegalStateException(message)

@Service
class LeaseService(
    private val repository: GatewayInstanceRepository,
    private val properties: GatewayProperties,
) {
    val instanceId: String = UUID.randomUUID().toString()
    val startedAt: Instant = Instant.now()

    /** 부팅 때 잠금을 잡는다 — 증권사 웹소켓 연결보다 먼저(ApplicationReady 이전) 실패해야 한다. */
    @PostConstruct
    fun acquire() {
        if (!repository.existsById(1L)) {
            runCatching { repository.save(GatewayInstance()) } // 동시에 둘이 만들면 하나는 실패한다 — 아래 tryAcquire 가 가른다
        }
        val now = Instant.now()
        val updated = repository.tryAcquire(instanceId, startedAt, now, now.plus(lease()), CONTRACT_VERSION)
        if (updated == 0) {
            val other = repository.findById(1L).orElse(null)
            throw GatewayAlreadyRunningException("다른 게이트웨이(${other?.instanceId})가 ${other?.leaseUntil} 까지 잠금을 쥐고 있어 뜨지 않는다")
        }
        log.info { "[게이트웨이] 단일 실행 잠금 획득: $instanceId" }
    }

    /** 하트비트를 쓰고 잠금을 늘린다. 잠금을 잃었으면(다른 인스턴스가 이어받음) false. */
    fun renew(
        wsConnectedAt: Instant?,
        liveSymbols: List<String>,
    ): Boolean {
        val now = Instant.now()
        val live = liveSymbols.joinToString(",").take(2000)
        return repository.renew(instanceId, now, now.plus(lease()), wsConnectedAt, live) > 0
    }

    private fun lease(): Duration = Duration.ofSeconds(properties.leaseSeconds)
}

package com.quantlog.gateway.record

import com.fasterxml.jackson.databind.ObjectMapper
import com.quantlog.gateway.GatewayProperties
import com.quantlog.gateway.kis.KisApiClient
import com.quantlog.gateway.kis.KisProperties
import com.quantlog.gateway.stream.StreamHub
import mu.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private val log = KotlinLogging.logger {}

/** 잔고 원문을 [KisBalance] 에 쌓고 오래된 줄을 지운다. 별도 빈이라 @Transactional 이 먹는다. */
@Service
class KisBalanceStore(
    private val repository: KisBalanceRepository,
) {
    private var lastPrunedAt: Instant = Instant.EPOCH

    /** 저장하고 새 줄의 id 를 돌려준다. */
    @Transactional
    fun record(
        body: String,
        fetchedAt: Instant,
        now: Instant = Instant.now(),
    ): Long {
        val saved = repository.save(KisBalance(fetchedAt, now, body))
        if (Duration.between(lastPrunedAt, now) > PRUNE_EVERY) {
            lastPrunedAt = now
            repository.deleteByReceivedAtBefore(now.minus(RETENTION))
        }
        return saved.id!!
    }

    private companion object {
        val RETENTION: Duration = Duration.ofDays(1)
        val PRUNE_EVERY: Duration = Duration.ofHours(1)
    }
}

/**
 * 한투 잔고(주식잔고조회 VTTC8434R)를 주기적으로(그리고 체결이 있을 때 바로) 받아 응답 원문을 DB 에 **기록만** 한다. 평단·수량 계산이나 사본과의 대조는 앱이 한다.
 * 느린 한투 호출 하나가 다른 일을 막지 않도록 전용 스레드에서 돌리고, 이전 회차가 안 끝났으면 건너뛴다.
 * 한투가 오류(`rt_cd` ≠ 0)로 답한 회차는 기록하지 않는다 — 오류 응답이 "보유 없음"처럼 읽히면 안 된다.
 */
@Component
class BalanceRecorder(
    private val api: KisApiClient,
    private val kis: KisProperties,
    private val objectMapper: ObjectMapper,
    private val store: KisBalanceStore,
    private val hub: StreamHub,
    private val properties: GatewayProperties,
) {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "balance-recorder").apply { isDaemon = true } }
    private val running = AtomicBoolean(false)
    private val refreshRequested = AtomicBoolean(false)

    @Scheduled(
        fixedDelayString = "\${quantlog.gateway.balance.interval-millis:10000}",
        initialDelayString = "\${quantlog.gateway.balance.initial-delay-millis:5000}",
    )
    fun run() {
        if (properties.balance.enabled) submit()
    }

    /** 체결이 있었으니 다음 주기를 기다리지 않고 바로 받는다(진행 중이면 그 회차가 끝난 뒤 한 번 더). */
    fun requestRefresh() {
        if (!properties.balance.enabled) return
        refreshRequested.set(true)
        submit()
    }

    private fun submit() {
        if (!running.compareAndSet(false, true)) return
        executor.execute {
            try {
                do {
                    refreshRequested.set(false)
                    syncNow()
                } while (refreshRequested.get())
            } finally {
                running.set(false)
            }
        }
    }

    @Synchronized
    fun syncNow() {
        if (!kis.hasCredentials) return
        val fetchedAt = Instant.now()
        val response =
            try {
                api.raw(BALANCE_PATH, BALANCE_TR_ID, KisApiClient.GET, BALANCE_PARAMS + accountParams(), null, null)
            } catch (e: Exception) {
                log.warn(e) { "[잔고 기록] 한투 잔고 조회 실패, 이번 회차 건너뜀" }
                return
            }
        val rtCd = runCatching { objectMapper.readTree(response.body).path("rt_cd").asText() }.getOrNull()
        if (rtCd != "0") {
            log.warn { "[잔고 기록] 한투가 오류로 답해 이번 회차 건너뜀: HTTP ${response.status} ${response.body.take(200)}" }
            return
        }
        runCatching { store.record(response.body, fetchedAt) }
            .onSuccess { hub.balanceRecorded(it) }
            .onFailure { log.error(it) { "[잔고 기록] DB 에 쓰지 못했다" } }
    }

    private fun accountParams() = mapOf("CANO" to kis.accountNumber, "ACNT_PRDT_CD" to kis.accountProductCode)

    private companion object {
        const val BALANCE_PATH = "/uapi/domestic-stock/v1/trading/inquire-balance"
        const val BALANCE_TR_ID = "VTTC8434R"
        val BALANCE_PARAMS =
            mapOf(
                "AFHR_FLPR_YN" to "N",
                "OFL_YN" to "",
                "INQR_DVSN" to "02",
                "UNPR_DVSN" to "01",
                "FUND_STTL_ICLD_YN" to "N",
                "FNCG_AMT_AUTO_RDPT_YN" to "N",
                "PRCS_DVSN" to "00",
                "CTX_AREA_FK100" to "",
                "CTX_AREA_NK100" to "",
            )
    }
}

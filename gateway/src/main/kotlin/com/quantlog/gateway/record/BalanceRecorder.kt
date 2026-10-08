package com.quantlog.gateway.record

import com.quantlog.gateway.GatewayProperties
import com.quantlog.gateway.broker.BrokerClient
import com.quantlog.gateway.broker.Holding
import com.quantlog.gateway.broker.Market
import com.quantlog.gateway.stream.StreamHub
import mu.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private val log = KotlinLogging.logger {}

/** KIS 잔고를 [BrokerBalance] 스냅샷에 그대로 덮어쓴다. 별도 빈이라 @Transactional 이 먹는다. */
@Service
class BalanceStore(
    private val repository: BrokerBalanceRepository,
    private val metaRepository: BrokerBalanceMetaRepository,
) {
    /** 저장하고 새 회차 번호를 돌려준다. 수량 0 인 종목은 뺀다. */
    @Transactional
    fun record(
        holdings: List<Holding>,
        fetchedAt: Instant,
        now: Instant = Instant.now(),
    ): Long {
        val actual = holdings.filter { it.quantity.signum() > 0 }.associateBy { it.market.name to it.symbol }
        val existing = repository.findAll().associateBy { it.market to it.symbol }
        actual.forEach { (key, h) ->
            val row = existing[key]
            if (row == null) {
                repository.save(BrokerBalance(key.first, key.second, h.name, h.quantity, h.averagePrice, h.currentPrice, now))
            } else {
                row.name = h.name
                row.quantity = h.quantity
                row.averagePrice = h.averagePrice
                row.currentPrice = h.currentPrice
                row.updatedAt = now
            }
        }
        existing.filterKeys { it !in actual }.values.forEach { repository.delete(it) }
        val meta = metaRepository.findById(1L).orElseGet { BrokerBalanceMeta() }
        meta.seq += 1
        meta.fetchedAt = fetchedAt
        meta.completedAt = now
        metaRepository.save(meta)
        return meta.seq
    }
}

/**
 * KIS 잔고를 주기적으로(그리고 체결이 있을 때 바로) 받아 DB 에 **기록만** 한다. 평단·수량 계산이나 사본과의 대조는 앱이 한다.
 * 느린 KIS 호출 하나가 다른 일을 막지 않도록 전용 스레드에서 돌리고, 이전 회차가 안 끝났으면 건너뛴다.
 */
@Component
class BalanceRecorder(
    private val broker: BrokerClient,
    private val store: BalanceStore,
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
        val fetchedAt = Instant.now()
        val holdings =
            try {
                broker.holdings(Market.KR)
            } catch (e: Exception) {
                log.warn(e) { "[잔고 기록] KIS 잔고 조회 실패, 이번 회차 건너뜀" }
                return
            }
        runCatching { store.record(holdings, fetchedAt) }
            .onSuccess { hub.balanceRecorded(it) }
            .onFailure { log.error(it) { "[잔고 기록] DB 에 쓰지 못했다" } }
    }
}

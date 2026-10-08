package com.quantlog.trading

import com.quantlog.broker.Market
import com.quantlog.broker.PriceTick
import jakarta.annotation.PreDestroy
import mu.KotlinLogging
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.ZonedDateTime
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private val log = KotlinLogging.logger {}

/**
 * 실시간 틱이 오면 그 종목만 마틴게일 판정한다. 틱은 WebSocket 수신 스레드에서 오므로 판정·주문(REST 수백 ms)은 별도 스레드로 넘긴다
 * ([ExitTickListener] 와 같은 구조). 같은 종목의 중복 처리는 [MartingaleService] 의 종목별 잠금과 "단계 진행 중" 상태가 막는다.
 * 국내(실시간 구독 종목)만 — 실시간이 끊긴 종목은 [EntryScheduler] 폴링이 맡는다.
 */
@Component
class MartingaleTickListener(
    private val martingaleService: MartingaleService,
    private val properties: EntryProperties,
) {
    /** 테스트에서 같은 스레드 실행기로 바꿔 끼울 수 있게 var. */
    internal var executor: Executor = Executors.newFixedThreadPool(WORKER_THREADS)

    @EventListener
    fun onTick(tick: PriceTick) {
        if (!properties.enabled || tick.market != Market.KR) return
        executor.execute {
            runCatching { martingaleService.checkSymbol(tick.market, tick.symbol, tick.price, ZonedDateTime.now()) }
                .onFailure { log.warn(it) { "[마틴게일] 틱 처리 실패: ${tick.market} ${tick.symbol}" } }
        }
    }

    @PreDestroy
    fun stop() {
        (executor as? ExecutorService)?.shutdown()
    }

    private companion object {
        const val WORKER_THREADS = 2
    }
}

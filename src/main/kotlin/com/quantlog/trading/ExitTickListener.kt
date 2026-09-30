package com.quantlog.trading

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
 * 실시간 틱이 오면 그 종목만 바로 청산 판정한다. 틱은 WebSocket 수신 스레드에서 오므로, 판정·주문(REST 수백 ms)은
 * 별도 스레드로 넘겨서 다른 종목의 틱 수신을 막지 않는다. 같은 종목의 중복 처리는 [ExitService] 의 종목별 잠금이 막는다.
 */
@Component
class ExitTickListener(
    private val exitService: ExitService,
    private val properties: ExitProperties,
) {
    /** 테스트에서 같은 스레드 실행기로 바꿔 끼울 수 있게 var. */
    internal var executor: Executor = Executors.newFixedThreadPool(WORKER_THREADS)

    @EventListener
    fun onTick(tick: PriceTick) {
        if (!properties.enabled) return
        executor.execute {
            runCatching { exitService.checkSymbol(tick.market, tick.symbol, ZonedDateTime.now()) }
                .onFailure { log.warn(it) { "[청산 감시] 틱 처리 실패: ${tick.market} ${tick.symbol}" } }
        }
    }

    @PreDestroy
    fun stop() {
        (executor as? ExecutorService)?.shutdown()
    }

    private companion object {
        const val WORKER_THREADS = 4
    }
}

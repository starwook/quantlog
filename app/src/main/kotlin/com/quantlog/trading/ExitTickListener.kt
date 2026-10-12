package com.quantlog.trading

import com.quantlog.broker.PriceTick
import jakarta.annotation.PreDestroy
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.ZonedDateTime

/**
 * 실시간 틱이 오면 그 종목만 바로 청산 판정한다. 같은 종목의 중복 처리는 [ExitService] 의 종목별 잠금이 막는다.
 */
@Component
class ExitTickListener(
    private val exitService: ExitService,
    private val properties: ExitProperties,
) {
    /** 테스트에서 [TickWorker.executor] 를 같은 스레드 실행기로 바꿔 끼운다. */
    internal val worker = TickWorker(WORKER_THREADS)

    @EventListener
    fun onTick(tick: PriceTick) {
        if (!properties.enabled) return
        worker.submit({ "[청산 감시] 틱 처리 실패: ${tick.market} ${tick.symbol}" }) {
            exitService.checkSymbol(tick.market, tick.symbol, ZonedDateTime.now())
        }
    }

    @PreDestroy
    fun stop() = worker.stop()

    private companion object {
        const val WORKER_THREADS = 4
    }
}

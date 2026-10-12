package com.quantlog.trading

import com.quantlog.broker.Market
import com.quantlog.broker.PriceTick
import jakarta.annotation.PreDestroy
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.ZonedDateTime

/**
 * 실시간 틱이 오면 그 종목만 마틴게일 판정한다([ExitTickListener] 와 같은 구조). 같은 종목의 중복 처리는 [MartingaleService] 의
 * 종목별 잠금과 "단계 진행 중" 상태가 막는다. 국내(실시간 구독 종목)만 — 실시간이 끊긴 종목은 [EntryScheduler] 폴링이 맡는다.
 */
@Component
class MartingaleTickListener(
    private val martingaleService: MartingaleService,
    private val properties: EntryProperties,
) {
    /** 테스트에서 [TickWorker.executor] 를 같은 스레드 실행기로 바꿔 끼운다. */
    internal val worker = TickWorker(WORKER_THREADS)

    @EventListener
    fun onTick(tick: PriceTick) {
        if (!properties.enabled || tick.market != Market.KR) return
        worker.submit({ "[마틴게일] 틱 처리 실패: ${tick.market} ${tick.symbol}" }) {
            martingaleService.checkSymbol(tick.market, tick.symbol, tick.price, ZonedDateTime.now())
        }
    }

    @PreDestroy
    fun stop() = worker.stop()

    private companion object {
        const val WORKER_THREADS = 2
    }
}

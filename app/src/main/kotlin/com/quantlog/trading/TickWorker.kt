package com.quantlog.trading

import mu.KotlinLogging
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private val log = KotlinLogging.logger {}

/**
 * 실시간 틱은 WebSocket 수신 스레드에서 오므로, 판정·주문(REST 수백 ms)은 이 작업 스레드로 넘겨 다른 종목의 틱 수신을 막지 않는다.
 * 틱 리스너([ExitTickListener]·[MartingaleTickListener])가 같이 쓴다.
 */
class TickWorker(threads: Int) {
    /** 테스트에서 같은 스레드 실행기로 바꿔 끼울 수 있게 var. */
    internal var executor: Executor = Executors.newFixedThreadPool(threads)

    /** [work] 를 작업 스레드에서 실행한다. 실패는 [failureMessage] 로 경고만 남기고 삼킨다(다음 틱이 다시 판정한다). */
    fun submit(
        failureMessage: () -> String,
        work: () -> Unit,
    ) = executor.execute { runCatching(work).onFailure { log.warn(it) { failureMessage() } } }

    fun stop() {
        (executor as? ExecutorService)?.shutdown()
    }
}

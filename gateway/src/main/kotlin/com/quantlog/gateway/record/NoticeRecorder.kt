package com.quantlog.gateway.record

import com.quantlog.gateway.kis.BrokerNoticeReceived
import com.quantlog.gateway.stream.StreamHub
import mu.KotlinLogging
import org.springframework.context.event.EventListener
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import java.time.Instant

private val log = KotlinLogging.logger {}

/**
 * 체결통보를 받는 즉시 원문 그대로 [BrokerNotice] 원장에 한 줄씩 쌓는다. 증권사가 실시간으로만 밀어 주고 다시 보내 주지 않는 응답이라,
 * **앱보다 먼저 이 서버가 받아 DB 에 써 둔다** — 앱이 재배포 중이어도 통보를 놓치지 않는 것이 서버를 나눈 이유다. 저장에 실패하면 잠깐
 * 재시도하고, 그래도 안 되면 ERROR 로그를 남긴다(앱의 체결 대조가 KIS 와 비교해 알린다). 접수인지 체결인지는 보지 않는다 — 통보가 오면
 * 잔고도 바로 다시 받는다.
 */
@Component
class NoticeRecorder(
    private val repository: BrokerNoticeRepository,
    private val hub: StreamHub,
    private val balances: BalanceRecorder,
) {
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @EventListener
    fun onNotice(notice: BrokerNoticeReceived) {
        val saved = saveWithRetry(BrokerNotice(Instant.now(), notice.trId, notice.body)) ?: return
        hub.fillRecorded(saved.id!!)
        balances.requestRefresh()
    }

    private fun saveWithRetry(row: BrokerNotice): BrokerNotice? {
        var last: Throwable? = null
        repeat(ATTEMPTS) { attempt ->
            try {
                return repository.save(row)
            } catch (e: Exception) {
                last = e
                if (attempt < ATTEMPTS - 1) Thread.sleep(RETRY_DELAY_MILLIS)
            }
        }
        log.error(last) { "[체결통보 기록 실패] 원장에 못 썼다: ${row.trId} ${row.body}" }
        return null
    }

    private companion object {
        const val ATTEMPTS = 3
        const val RETRY_DELAY_MILLIS = 200L
    }
}

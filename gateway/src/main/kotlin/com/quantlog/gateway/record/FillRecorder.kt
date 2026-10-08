package com.quantlog.gateway.record

import com.quantlog.gateway.broker.FillNotice
import com.quantlog.gateway.stream.StreamHub
import mu.KotlinLogging
import org.springframework.context.event.EventListener
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import java.time.Instant

private val log = KotlinLogging.logger {}

/**
 * 체결통보를 받는 즉시 [BrokerFill] 원장에 한 줄씩 쌓는다. 증권사가 실시간으로만 밀어 주고 다시 보내 주지 않는 응답이라, **앱보다 먼저 이 서버가
 * 받아 DB 에 써 둔다** — 앱이 재배포 중이어도 통보를 놓치지 않는 것이 서버를 나눈 이유다. 저장에 실패하면 잠깐 재시도하고, 그래도 안 되면
 * 통보 내용을 ERROR 로그에 남긴다(앱의 REST 체결 보충이 마지막 안전망이다).
 */
@Component
class FillRecorder(
    private val repository: BrokerFillRepository,
    private val hub: StreamHub,
    private val balances: BalanceRecorder,
) {
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @EventListener
    fun onFillNotice(notice: FillNotice) {
        val row =
            BrokerFill(
                receivedAt = Instant.now(),
                symbol = notice.symbol,
                orderNo = notice.orderNo,
                originalOrderNo = notice.originalOrderNo,
                sellBuyCode = notice.sellBuyCode,
                filledFlag = notice.filledFlag,
                acceptFlag = notice.acceptFlag,
                refuseFlag = notice.refuseFlag,
                filledQuantity = notice.filledQuantity,
                filledPrice = notice.filledPrice,
                orderQuantity = notice.orderQuantity,
                orderPrice = notice.orderPrice,
                noticeTime = notice.time,
            )
        val saved = saveWithRetry(row, notice) ?: return
        hub.fillRecorded(saved.id!!)
        // 체결되면 10초 주기를 기다리지 않고 잔고도 바로 다시 받는다.
        if (notice.isFill) balances.requestRefresh()
    }

    private fun saveWithRetry(
        row: BrokerFill,
        notice: FillNotice,
    ): BrokerFill? {
        var last: Throwable? = null
        repeat(ATTEMPTS) { attempt ->
            try {
                return repository.save(row)
            } catch (e: Exception) {
                last = e
                if (attempt < ATTEMPTS - 1) Thread.sleep(RETRY_DELAY_MILLIS)
            }
        }
        log.error(last) { "[체결통보 기록 실패] 원장에 못 썼다 — 앱의 REST 체결 보충이 채워야 한다: ${notice.summary()}" }
        return null
    }

    private companion object {
        const val ATTEMPTS = 3
        const val RETRY_DELAY_MILLIS = 200L
    }
}

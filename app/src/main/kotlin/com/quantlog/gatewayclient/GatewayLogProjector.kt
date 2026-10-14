package com.quantlog.gatewayclient

import com.quantlog.errorlog.ErrorLogClassifier
import com.quantlog.errorlog.ErrorLogRecorder
import com.quantlog.errorlog.LogRecord
import com.quantlog.notification.Notifier
import mu.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

private val log = KotlinLogging.logger {}

/**
 * 게이트웨이가 원본 그대로 쌓은 로그(`gateway_log`)를 읽어 앱의 오류 기록(`error_log`, /errors 화면)에 같은 종류끼리 묶어 넣고 웹훅으로 알린다.
 * 게이트웨이는 해석도 알림도 하지 않으므로 묶기·분류·알림은 앱이 한다. 어디까지 읽었는지는 `broker_projection_cursor` 에 둔다 —
 * 앱이 재배포로 꺼져 있던 동안 쌓인 로그도 켜진 뒤 이어서 읽는다. 커서가 없으면(처음 켠 때) 지금 있는 마지막 줄부터 시작한다.
 * 같은 오류 알림은 1분에 한 번만 보낸다.
 *
 * 동기화 불일치 보고 불필요: 증권사와 비교하지 않는다. 게이트웨이가 남긴 로그를 앱의 오류 기록으로 옮겨 적는 일이다.
 */
@Component
class GatewayLogProjector(
    private val rows: GatewayLogRowRepository,
    private val cursors: ProjectionCursorRepository,
    private val recorder: ErrorLogRecorder,
    private val notifier: Notifier,
) {
    private val lastSentAt = ConcurrentHashMap<String, Long>()

    @Scheduled(fixedDelay = 5_000, initialDelay = 5_000)
    fun run() {
        runCatching { project() }.onFailure { log.debug(it) { "[게이트웨이 로그] 반영 실패" } }
    }

    /** 처리한 줄 수를 돌려준다. */
    @Synchronized
    fun project(): Int {
        var last = cursors.findById(CURSOR).map { it.lastId }.orElse(null)
        if (last == null) {
            cursors.save(ProjectionCursor(CURSOR, rows.maxId() ?: 0))
            return 0
        }
        var processed = 0
        while (true) {
            val batch = rows.findTop100ByIdGreaterThanOrderByIdAsc(last!!)
            if (batch.isEmpty()) return processed
            batch.forEach { row ->
                runCatching { forward(row.toRecord()) }.onFailure { log.debug(it) { "[게이트웨이 로그] 줄 반영 실패: id=${row.id}" } }
                last = row.id
                cursors.save(ProjectionCursor(CURSOR, row.id!!))
                processed++
            }
        }
    }

    private fun forward(record: LogRecord) {
        recorder.record(record)
        if (!notifier.enabled) return
        val now = System.currentTimeMillis()
        val previous = lastSentAt.put(ErrorLogClassifier.fingerprint(record), now)
        if (previous != null && now - previous < DEDUPE_MILLIS) return
        val icon = if (record.level == "ERROR") "🚨" else "⚠️"
        val cause = record.exceptionClass?.let { "\n$it: ${record.exceptionMessage.orEmpty()}" } ?: ""
        notifier.send("$icon [quantlog gateway ${record.level}] ${record.message}$cause")
    }

    private fun GatewayLogRow.toRecord() = LogRecord(loggedAt, level, logger, message, exceptionClass, exceptionMessage, stackTrace)

    internal companion object {
        const val CURSOR = "gateway_log"
        const val DEDUPE_MILLIS = 60_000L
    }
}

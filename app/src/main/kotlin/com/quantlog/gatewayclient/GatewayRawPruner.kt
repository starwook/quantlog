package com.quantlog.gatewayclient

import com.quantlog.errorlog.ErrorLogProperties
import mu.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration

private val log = KotlinLogging.logger {}

/**
 * 게이트웨이가 쌓은 원문 테이블(`kis_minute_chart`·`kis_balance`·`gateway_log`)에서 오래된 줄을 지운다(2026-10-14: 게이트웨이에서 앱으로 옮김).
 * 게이트웨이는 기록·전달만 하고 "얼마나 보관할지"는 정책이라 앱이 정한다 — 보관 기간을 바꾸려고 게이트웨이를 다시 띄우지 않는다.
 *
 * 앱이 아직 읽지 않은 줄은 지우지 않는다: 분봉·게이트웨이 로그는 앱이 어디까지 읽었는지(`broker_projection_cursor`)를 넘지 않는 줄만,
 * 잔고는 앱이 쓰는 가장 큰 id 한 줄을 남기고 그보다 앞선 줄만 지운다. 앱이 오래 꺼져 있었어도 읽기 전의 줄이 사라지지 않는다.
 *
 * 동기화 불일치 보고 불필요: 증권사와 비교하지 않는다. 앱이 이미 처리한 원문을 정리할 뿐이다.
 */
@Component
class GatewayRawPruner(
    private val charts: KisMinuteChartRowRepository,
    private val balances: KisBalanceRowRepository,
    private val logs: GatewayLogRowRepository,
    private val cursors: ProjectionCursorRepository,
    private val errorLogProperties: ErrorLogProperties,
    private val clock: Clock,
) {
    @Scheduled(cron = "0 5 * * * *", zone = "Asia/Seoul")
    fun run() {
        runCatching { prune() }.onFailure { log.warn(it) { "[원문 정리] 실패, 다음 회차에 재시도: ${it.message}" } }
    }

    /** 지운 줄 수(분봉·잔고·게이트웨이 로그 순). 한 테이블이 실패해도 나머지는 지우도록 테이블마다 따로 돈다. */
    fun prune(): Triple<Int, Int, Int> {
        val now = clock.instant()
        val rawCutoff = now.minus(RAW_RETENTION)
        val logCutoff = now.minus(Duration.ofDays(errorLogProperties.retentionDays))

        val chartsDeleted =
            attempt("분봉") { cursors.findById(CandleProjector.CURSOR).map { charts.deleteReadBefore(rawCutoff, it.lastId) }.orElse(0) }
        val balancesDeleted =
            attempt("잔고") { balances.findTopByOrderByIdDesc()?.id?.let { balances.deleteOlderBefore(rawCutoff, it) } ?: 0 }
        val logsDeleted =
            attempt("게이트웨이 로그") {
                cursors.findById(GatewayLogProjector.CURSOR).map { logs.deleteReadBefore(logCutoff, it.lastId) }.orElse(0)
            }

        if (chartsDeleted + balancesDeleted + logsDeleted > 0) {
            log.info { "[원문 정리] 분봉 ${chartsDeleted}건, 잔고 ${balancesDeleted}건, 게이트웨이 로그 ${logsDeleted}건 삭제" }
        }
        return Triple(chartsDeleted, balancesDeleted, logsDeleted)
    }

    private fun attempt(
        name: String,
        delete: () -> Int,
    ): Int = runCatching(delete).getOrElse { 0.also { _ -> log.warn(it) { "[원문 정리] $name 삭제 실패, 다음 회차에 재시도: ${it.message}" } } }

    private companion object {
        /** 분봉·잔고 원문 보관 기간. 게이트웨이가 지우던 값 그대로(1일). 게이트웨이 로그는 오류 기록 보관 일수([ErrorLogProperties.retentionDays])를 따른다. */
        val RAW_RETENTION: Duration = Duration.ofDays(1)
    }
}

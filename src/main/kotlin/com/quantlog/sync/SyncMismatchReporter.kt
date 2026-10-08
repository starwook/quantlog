package com.quantlog.sync

import mu.KotlinLogging

private val log = KotlinLogging.logger {}

/**
 * 외부(증권사) 데이터와 우리 DB 를 맞추는 로직이 "둘이 달랐다"는 걸 발견했을 때 부르는 공용 창구다.
 * ERROR 로그 한 줄을 남길 뿐 예외를 던지지 않는다 — 동기화는 계속 진행되고(보통 외부 값으로 DB 를 고친다), 어긋났다는 사실만 기록으로 남는다.
 * 로그 앞의 `[동기화 불일치]` 태그 덕에 기존 오류 기록(ErrorLogAppender → `error_log`, 화면 `/errors`)과 웹훅 알림에 그대로 실린다.
 *
 * **규칙:** 외부 API 와 DB 를 비교해서 고치는 로직을 새로 만들면, 다를 때마다 여기로 보고한다(CLAUDE.md "동기화 불일치 보고").
 * 이름에 Sync/Reconcil 이 들어간 클래스가 이 객체를 안 쓰면 `SyncMismatchRuleTest` 가 실패한다.
 */
object SyncMismatchReporter {
    const val TAG = "[동기화 불일치]"

    /**
     * @param area 어떤 동기화인가 (예: "잔고 동기화"). 오류 기록에서 같은 종류끼리 묶이는 기준의 일부다.
     * @param subject 무엇이 어긋났나 (예: 종목명, 주문번호).
     * @param db 우리 DB 가 가지고 있던 값.
     * @param external 증권사(외부)가 알려준 값.
     * @param action 어떻게 처리했나 (예: "DB 를 증권사 값으로 고침").
     */
    fun report(
        area: String,
        subject: String,
        db: String,
        external: String,
        action: String,
    ) {
        log.error { "$TAG $area $subject: DB=$db / 증권사=$external — $action" }
    }
}

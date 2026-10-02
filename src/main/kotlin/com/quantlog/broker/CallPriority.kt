package com.quantlog.broker

/**
 * 증권사 호출 우선순위 매뉴얼.
 *
 * 증권사 REST 는 초당 호출 한도가 있어서 모든 호출이 하나의 호출 간격 대기열을 같이 쓴다(KisApiClient.throttle).
 * 스케줄러(진입·청산·잔고 동기화·분봉 수집·체결 재확인)가 매초 호출을 쌓으면 사용자가 방금 누른 주문·취소가 그 뒤에 밀린다.
 * 그래서 호출에는 두 등급이 있다:
 *
 * - **즉발(urgent)** — 사용자가 직접 요청했거나 주문 흐름에 속한 호출. 주문·취소·그 직전의 시세·체결 조회 전부.
 *   대기 중인 스케줄러 호출보다 항상 먼저 나간다. [urgent] 블록 안에서 부른 호출이 여기에 속한다.
 * - **일반(background)** — 기본값. 스케줄러의 주기 호출은 모두 여기라서 즉발 호출이 대기 중이면 양보한다. 스케줄링은 최후순위다.
 *
 * 새 기능을 만들 때: 사용자의 클릭에 바로 응답해야 하거나(수동 주문·취소), 늦으면 손해인 주문 경로(청산·진입 주문)면
 * 그 흐름 전체를 [urgent] 로 감싼다. 주기적으로 도는 조회·수집은 감싸지 않는다(기본이 일반이다).
 * 즉발 블록은 짧게 유지한다 — 오래 걸리는 일을 감싸면 스케줄러가 굶는다.
 */
object CallPriority {
    private val current = ThreadLocal.withInitial { false }

    /** 현재 스레드의 호출이 즉발 등급인가. */
    fun isUrgent(): Boolean = current.get()

    /** [block] 안의 증권사 호출을 즉발 등급으로 보낸다. 중첩해도 된다. */
    inline fun <T> urgent(block: () -> T): T {
        val previous = enter()
        try {
            return block()
        } finally {
            restore(previous)
        }
    }

    @PublishedApi
    internal fun enter(): Boolean = current.get().also { current.set(true) }

    @PublishedApi
    internal fun restore(previous: Boolean) = current.set(previous)
}

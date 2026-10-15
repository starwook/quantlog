package com.quantlog.sync

import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import mu.KotlinLogging
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.util.concurrent.Executors

private val log = KotlinLogging.logger {}

/** [SyncEvent] 의 종류. 화면(/errors)에 [label] 로 보인다. */
enum class SyncEventKind(val label: String) {
    /** 한투 체결통보를 못 받게 됐다(게이트웨이 `kis_ws` false). */
    KIS_DOWN("한투 연결 끊김"),

    /** 한투 체결통보를 다시 받는다(재연결·재구독). */
    KIS_UP("한투 재연결"),

    /** 모든 자동·수동 주문을 멈췄다. */
    HALT("거래 정지"),

    /** 멈췄던 주문을 다시 열었다. */
    RESUME("거래 재개"),

    /** 한투 체결조회가 실패했다(정지 중이면 1초마다 다시 시도한다). */
    INQUIRY_FAILED("체결조회 실패"),

    /** 외부(증권사)와 DB 가 달랐다 — [SyncMismatchReporter] 가 보고한 것 전부. */
    MISMATCH("동기화 불일치"),
}

/**
 * 동기화·연결 사건 기록(`sync_event`, 앱 소유, 추가만 한다). `error_log` 는 같은 종류를 한 줄로 묶어 개별 시각이 사라지므로,
 * "언제 끊겼고 언제 멈췄고 무엇을 고쳤는지"를 한 건씩 시간순으로 남긴다(2026-10-14 사용자 요청: 어긋남·끊김은 확실히 기록).
 */
@Entity
@Table(name = "sync_event")
class SyncEvent(
    @Column(name = "occurred_at", nullable = false)
    val occurredAt: Instant,
    @Column(nullable = false, length = 30)
    val kind: String,
    @Column(nullable = false, length = 200)
    val subject: String,
    @Column(nullable = false, length = 1000)
    val detail: String,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set
}

interface SyncEventRepository : JpaRepository<SyncEvent, Long> {
    fun findTop300ByOccurredAtAfterOrderByOccurredAtDesc(after: Instant): List<SyncEvent>
}

/**
 * [SyncEvent] 를 쌓는다. 부른 쪽의 트랜잭션과 묶이지 않게(되돌려져도 기록은 남게) 전용 스레드에서 저장하고, 저장이 실패해도 부른 쪽을 막지 않는다.
 * [SyncMismatchReporter] 가 보고하는 불일치도 여기로 들어온다([register]).
 */
@Component
class SyncEventRecorder(
    private val repository: SyncEventRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "sync-event").apply { isDaemon = true } }

    @PostConstruct
    fun register() {
        SyncMismatchReporter.sink = { subject, detail -> record(SyncEventKind.MISMATCH, subject, detail) }
    }

    @PreDestroy
    fun unregister() {
        SyncMismatchReporter.sink = null
        executor.shutdown()
    }

    fun record(
        kind: SyncEventKind,
        subject: String,
        detail: String,
        at: Instant = clock.instant(),
    ) {
        val event = SyncEvent(at, kind.name, subject.take(SUBJECT_MAX), detail.take(DETAIL_MAX))
        executor.execute {
            runCatching { repository.save(event) }.onFailure { log.warn { "[동기화 기록] 저장 실패(기록만 빠진다): ${it.message}" } }
        }
    }

    private companion object {
        const val SUBJECT_MAX = 200
        const val DETAIL_MAX = 1000
    }
}

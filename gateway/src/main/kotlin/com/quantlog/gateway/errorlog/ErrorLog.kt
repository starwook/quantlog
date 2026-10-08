package com.quantlog.gateway.errorlog

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

@ConfigurationProperties(prefix = "quantlog.error-log")
data class ErrorLogProperties(
    /** false 면 로그를 DB에 쌓지 않는다(테스트가 개발자 DB에 행을 남기지 않게 끈다). */
    val enabled: Boolean = true,
    /** 마지막 발생이 이 일수보다 오래된 행은 매일 새벽에 지운다. */
    val retentionDays: Long = 14,
)

/**
 * WARN 이상 로그를 "같은 종류끼리 묶어" 한 행으로 쌓는다 — 같은 오류가 3초마다 반복돼도 행이 늘지 않고 [count]·[lastSeen] 만 오른다.
 * 같은 종류의 기준은 [fingerprint](레벨·로거·숫자를 지운 메시지·예외 종류). 서버 로그를 직접 못 보는 상황에서
 * "어떤 오류가 언제부터 몇 번 났나"를 화면(/errors)에서 보려는 용도다. 시크릿이 메시지에 섞이지 않게 길이를 자른다.
 */
@Entity
@Table(name = "error_log", uniqueConstraints = [UniqueConstraint(columnNames = ["fingerprint"])])
class ErrorLog(
    @Column(nullable = false, length = 64)
    val fingerprint: String,
    @Column(nullable = false, length = 10)
    val level: String,
    /** 메시지 앞의 `[청산 감시]` 같은 태그, 없으면 로거 클래스 이름. 화면의 분류 기준이다. */
    @Column(nullable = false, length = 100)
    val category: String,
    @Column(nullable = false, length = 200)
    val logger: String,
    @Column(nullable = false, length = 1000)
    var message: String,
    @Column(name = "exception_class", length = 200)
    var exceptionClass: String? = null,
    @Column(name = "exception_message", length = 500)
    var exceptionMessage: String? = null,
    @Column(name = "stack_trace", columnDefinition = "text")
    var stackTrace: String? = null,
    @Column(name = "first_seen", nullable = false)
    val firstSeen: Instant,
    @Column(name = "last_seen", nullable = false)
    var lastSeen: Instant,
    @Column(nullable = false)
    var count: Long = 1,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set
}

interface ErrorLogRepository : JpaRepository<ErrorLog, Long> {
    fun findByFingerprint(fingerprint: String): ErrorLog?

    fun findAllByLastSeenAfterOrderByLastSeenDesc(after: Instant): List<ErrorLog>

    fun deleteByLastSeenBefore(before: Instant): Long
}

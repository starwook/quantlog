package com.quantlog.gateway.errorlog

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

@ConfigurationProperties(prefix = "quantlog.error-log")
data class ErrorLogProperties(
    /** false 면 로그를 DB에 쌓지 않는다(테스트가 개발자 DB에 행을 남기지 않게 끈다). */
    val enabled: Boolean = true,
)

/**
 * 게이트웨이가 낸 WARN 이상 로그 한 건의 원본(`gateway_log`). 묶기·분류·알림은 하지 않고 그대로 쌓기만 한다 —
 * 해석은 앱이 읽어 간다(docs/서버-분리.md "해석 금지"). 앱이 꺼져 있던 동안의 오류도 앱이 켜지면 이어서 읽는다. docs/contracts/README.md.
 */
@Entity
@Table(name = "gateway_log")
class GatewayLog(
    @Column(name = "logged_at", nullable = false)
    val loggedAt: Instant,
    @Column(nullable = false, length = 10)
    val level: String,
    @Column(nullable = false, length = 200)
    val logger: String,
    @Column(nullable = false, length = 1000)
    val message: String,
    @Column(name = "exception_class", length = 200)
    val exceptionClass: String? = null,
    @Column(name = "exception_message", length = 500)
    val exceptionMessage: String? = null,
    @Column(name = "stack_trace", columnDefinition = "text")
    val stackTrace: String? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set
}

interface GatewayLogRepository : JpaRepository<GatewayLog, Long>

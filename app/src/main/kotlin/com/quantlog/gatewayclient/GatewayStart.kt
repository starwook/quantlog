package com.quantlog.gatewayclient

import com.quantlog.common.KST
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import mu.KotlinLogging
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeFormatter

private val log = KotlinLogging.logger {}
private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(KST)

/**
 * 게이트웨이가 새로 뜬 기록(`gateway_start`, 앱 소유) — 인스턴스마다 한 줄. 증권사 웹소켓 끊김이 "한투가 끊은 것"인지 "우리가 게이트웨이를 다시 띄운 것"인지
 * 시각으로 가려 보려는 용도다(/errors 화면). [startedAt] 은 게이트웨이가 `gateway_instance.started_at` 에 적은 자기 시작 시각이고,
 * [previousHeartbeatAt] 은 앱이 직전 인스턴스에서 마지막으로 본 하트비트(= 그 인스턴스가 꺼진 시각의 근사, 앱이 그 사이 꺼져 있었으면 모른다)다.
 */
@Entity
@Table(name = "gateway_start", uniqueConstraints = [UniqueConstraint(columnNames = ["instance_id"])])
class GatewayStart(
    @Column(name = "instance_id", nullable = false, length = 64)
    val instanceId: String,
    @Column(name = "started_at", nullable = false)
    val startedAt: Instant,
    @Column(name = "previous_heartbeat_at")
    val previousHeartbeatAt: Instant?,
    @Column(name = "detected_at", nullable = false)
    val detectedAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set
}

interface GatewayStartRepository : JpaRepository<GatewayStart, Long> {
    fun existsByInstanceId(instanceId: String): Boolean

    fun findAllByStartedAtAfterOrderByStartedAtDesc(after: Instant): List<GatewayStart>
}

/**
 * 하트비트 행(`gateway_instance`)의 인스턴스 ID 가 처음 보는 값이면 게이트웨이가 새로 뜬 것으로 보고 [GatewayStart] 한 줄과
 * `[게이트웨이 시작]` WARN(→ `error_log`·웹훅)을 남긴다. 이미 기록한 인스턴스인지는 DB 로 확인하므로 앱이 재배포돼도 같은 시작을 두 번 적지 않고,
 * 앱이 꺼져 있던 사이의 재시작도 켜진 뒤 한 번 적는다. 게이트웨이는 고치지 않는다 — 앱이 이미 읽는 하트비트 행만 본다.
 *
 * 동기화 불일치 보고 불필요: 증권사와 비교하지 않는다. 게이트웨이 인스턴스가 바뀐 사실만 적는다.
 */
@Component
class GatewayStartRecorder(
    private val starts: GatewayStartRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    private var recordedId: String? = null
    private var lastSeen: Pair<String, Instant>? = null

    /** [GatewayWatcher] 가 하트비트 행을 읽을 때마다 부른다(한 스레드). */
    fun observe(row: GatewayInstanceRow) {
        if (row.instanceId.isBlank()) return
        val previous = lastSeen
        lastSeen = row.instanceId to row.heartbeatAt
        if (row.instanceId == recordedId) return
        if (!starts.existsByInstanceId(row.instanceId)) {
            val previousHeartbeat = previous?.takeIf { it.first != row.instanceId }?.second
            starts.save(GatewayStart(row.instanceId, row.startedAt, previousHeartbeat, clock.instant()))
            val before = previousHeartbeat?.let { TIME.format(it) } ?: "모름"
            log.warn { "[게이트웨이 시작] 게이트웨이가 새로 떴다: 시작 ${TIME.format(row.startedAt)}, 직전 인스턴스의 마지막 하트비트 $before" }
        }
        recordedId = row.instanceId
    }
}

package com.quantlog.errorlog

import com.quantlog.common.KST
import com.quantlog.gatewayclient.GatewayStart
import com.quantlog.gatewayclient.GatewayStartRepository
import com.quantlog.sync.SyncEvent
import com.quantlog.sync.SyncEventKind
import com.quantlog.sync.SyncEventRepository
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter

private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(KST)
private const val MAX_ROWS = 200
private val PERIOD_DAYS = listOf(1L, 7L, 14L)

data class ErrorLogRowView(
    val lastSeen: String,
    val firstSeen: String,
    val level: String,
    val levelCss: String,
    val category: String,
    val message: String,
    val exceptionLine: String?,
    val stackTrace: String?,
    val countText: String,
)

data class CategoryChip(val name: String, val count: Int)

/** 게이트웨이가 새로 뜬 기록 한 줄. [previousHeartbeat] 는 직전 인스턴스가 마지막으로 살아 있던 시각(모르면 null). */
data class GatewayStartView(val startedAt: String, val previousHeartbeat: String?)

/** 동기화·연결 사건 한 건(`sync_event`). [kindCss] 는 끊김·정지·불일치처럼 주의할 것만 강조한다. */
data class SyncEventView(val at: String, val kind: String, val kindCss: String, val subject: String, val detail: String)

/**
 * 서버 오류 기록 화면. 같은 종류의 WARN 이상 로그가 한 줄로 묶여 있고(몇 번, 언제부터 언제까지),
 * 분류(로그 앞 `[태그]`)·레벨·기간으로 걸러 본다. 로그를 서버에서 직접 못 읽는 상황에서 오류를 놓치지 않으려는 화면이다.
 * 같은 기간에 게이트웨이가 새로 뜬 시각도 함께 보여 준다 — 증권사 웹소켓 끊김이 재시작 때문인지 시각으로 가려 보려고.
 * 동기화·연결 사건(`sync_event`: 한투 끊김·재연결, 거래 정지·재개, 동기화 불일치, 체결조회 실패)은 묶지 않고 한 건씩 시간순으로 보여 준다.
 */
@Controller
class ErrorLogController(
    private val repository: ErrorLogRepository,
    private val gatewayStarts: GatewayStartRepository,
    private val syncEvents: SyncEventRepository,
) {
    @GetMapping("/errors")
    fun page(
        @RequestParam(defaultValue = "ALL") level: String,
        @RequestParam(required = false) category: String?,
        @RequestParam(defaultValue = "7") days: Long,
        model: Model,
    ): String {
        val period = days.takeIf { it in PERIOD_DAYS } ?: 7L
        val since = Instant.now().minus(Duration.ofDays(period))
        val inPeriod = repository.findAllByLastSeenAfterOrderByLastSeenDesc(since)
        val byLevel = inPeriod.filter { level == "ALL" || it.level == level }
        // 레벨 칩을 눌러도 분류가 URL 에 남는다. 그 분류가 이 레벨에는 없으면(WARN 분류 선택 후 ERROR 로 바꾼 경우)
        // 0건으로 비어 보이니 분류 필터를 풀고 전체를 보여 준다.
        val selectedCategory = category?.takeIf { c -> byLevel.any { it.category == c } }.orEmpty()
        val shown = byLevel.filter { selectedCategory.isEmpty() || it.category == selectedCategory }

        model.addAttribute("level", level)
        model.addAttribute("category", selectedCategory)
        model.addAttribute("days", period)
        model.addAttribute("periodDays", PERIOD_DAYS)
        model.addAttribute("rows", shown.take(MAX_ROWS).map { it.toView() })
        model.addAttribute("truncated", shown.size > MAX_ROWS)
        model.addAttribute("groupCount", shown.size)
        model.addAttribute("occurrenceCount", shown.sumOf { it.count })
        model.addAttribute("errorGroups", inPeriod.count { it.level == "ERROR" })
        model.addAttribute("warnGroups", inPeriod.count { it.level == "WARN" })
        model.addAttribute(
            "categories",
            byLevel.groupingBy { it.category }.eachCount().map { CategoryChip(it.key, it.value) }.sortedByDescending { it.count },
        )
        model.addAttribute("gatewayStarts", gatewayStarts.findAllByStartedAtAfterOrderByStartedAtDesc(since).map { it.toView() })
        model.addAttribute("syncEvents", syncEvents.findTop300ByOccurredAtAfterOrderByOccurredAtDesc(since).map { it.toView() })
        return "errors"
    }

    private fun SyncEvent.toView(): SyncEventView {
        val kind = SyncEventKind.entries.firstOrNull { it.name == kind }
        val alert = kind in setOf(SyncEventKind.KIS_DOWN, SyncEventKind.HALT, SyncEventKind.MISMATCH, SyncEventKind.INQUIRY_FAILED)
        return SyncEventView(TIME.format(occurredAt), kind?.label ?: this.kind, if (alert) "warn" else "", subject, detail)
    }

    private fun GatewayStart.toView() = GatewayStartView(TIME.format(startedAt), previousHeartbeatAt?.let { TIME.format(it) })

    private fun ErrorLog.toView() =
        ErrorLogRowView(
            lastSeen = TIME.format(lastSeen),
            firstSeen = TIME.format(firstSeen),
            level = level,
            levelCss = if (level == "ERROR") "neg-strong" else "warn",
            category = category,
            message = message,
            exceptionLine = exceptionClass?.let { "${it.substringAfterLast('.')}: ${exceptionMessage.orEmpty()}" },
            stackTrace = stackTrace,
            countText = "%,d".format(count) + "회",
        )
}

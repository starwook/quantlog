package com.quantlog.errorlog

import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val KST: ZoneId = ZoneId.of("Asia/Seoul")
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

/**
 * 서버 오류 기록 화면. 같은 종류의 WARN 이상 로그가 한 줄로 묶여 있고(몇 번, 언제부터 언제까지),
 * 분류(로그 앞 `[태그]`)·레벨·기간으로 걸러 본다. 로그를 서버에서 직접 못 읽는 상황에서 오류를 놓치지 않으려는 화면이다.
 */
@Controller
class ErrorLogController(private val repository: ErrorLogRepository) {
    @GetMapping("/errors")
    fun page(
        @RequestParam(defaultValue = "ALL") level: String,
        @RequestParam(required = false) category: String?,
        @RequestParam(defaultValue = "7") days: Long,
        model: Model,
    ): String {
        val period = days.takeIf { it in PERIOD_DAYS } ?: 7L
        val inPeriod = repository.findAllByLastSeenAfterOrderByLastSeenDesc(Instant.now().minus(Duration.ofDays(period)))
        val byLevel = inPeriod.filter { level == "ALL" || it.level == level }
        val shown = byLevel.filter { category.isNullOrBlank() || it.category == category }

        model.addAttribute("level", level)
        model.addAttribute("category", category.orEmpty())
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
        return "errors"
    }

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

package com.quantlog.review

import mu.KotlinLogging
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.springframework.beans.factory.annotation.Value
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val log = KotlinLogging.logger {}

// "더 보기"는 한 번에 이만큼씩 늘린다. 날이 아무리 많아도 한 화면에서 읽는 양은 limit 으로 막는다.
private const val PAGE_STEP = 20
private const val PAGE_MAX = 1000

private val KST: ZoneId = ZoneId.of("Asia/Seoul")
private val CLOCK_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

/** 화면에 보여줄 제안 카드. [html] 은 본문 마크다운을 HTML 로 바꾼 것(원문의 HTML 태그는 이스케이프). */
data class ProposalView(
    val id: Long,
    val title: String,
    val status: String,
    val favorite: Boolean,
    val reviewDay: LocalDate,
    val html: String,
)

data class DayRow(val day: LocalDate, val label: String, val journals: Int, val proposals: Int, val favorites: Int)

private val markdownParser = Parser.builder().build()
private val markdownRenderer = HtmlRenderer.builder().escapeHtml(true).build()

private fun renderMarkdown(markdown: String): String = markdownRenderer.render(markdownParser.parse(markdown))

// 일지 파일은 "# 일지 날짜" 아래에 "## 15:31:46 KST — 일일 리뷰"(생성 시각) 다음 본문 절들이 이어진다. 시각 줄은 절이 아니라 메타 정보다.
private val ENTRY_HEADING = Regex("""^(\d{2}:\d{2})(?::\d{2})?\s*KST\s*[—-]?\s*(.*)$""")

private data class JournalSection(val tone: String, val order: Int, val title: String, val body: String)

// 절 제목의 키워드 → (색 계열, 보여줄 순서). 일지 형식이 바뀌어도 알려진 절은 항상 이 순서로 나온다.
private fun toneOf(title: String): Pair<String, Int> =
    when {
        "결론" in title || "요약" in title -> "sum" to 0
        "잘" in title -> "good" to 1
        "아쉬" in title -> "bad" to 2
        "규칙" in title || "원칙" in title -> "rule" to 3
        "데이터" in title -> "data" to 4
        else -> "plain" to 5
    }

private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

/**
 * 일지(마크다운)를 읽기 좋게 바꾼다: 생성 시각은 맨 위 작은 줄로, 본문 절은 색 계열 클래스를 달아 정해진 순서로 보여준다.
 * 알려진 절이 하나도 없는 옛 형식이면 그냥 마크다운 그대로 그린다.
 */
internal fun renderJournal(markdown: String): String {
    // 실행(생성 시각) 하나 = 한 묶음. 같은 날 여러 번 돌린 일지도 절이 섞이지 않는다.
    class Entry(val time: String, val sections: MutableList<JournalSection> = mutableListOf())

    val entries = mutableListOf(Entry(""))
    var title: String? = null
    val body = StringBuilder()

    fun flush() {
        val heading = title
        if (heading != null && body.isNotBlank()) {
            val (tone, order) = toneOf(heading)
            entries.last().sections += JournalSection(tone, order, heading, body.toString())
        }
        body.clear()
    }

    markdown.lineSequence().forEach { line ->
        when {
            line.startsWith("# ") -> Unit
            line.startsWith("## ") -> {
                flush()
                val heading = line.removePrefix("## ").trim()
                val entry = ENTRY_HEADING.matchEntire(heading)
                if (entry != null) {
                    entries += Entry(listOf(entry.groupValues[1], entry.groupValues[2]).filter { it.isNotBlank() }.joinToString(" · "))
                    title = null
                } else {
                    title = heading
                }
            }
            else -> body.appendLine(line)
        }
    }
    flush()

    if (entries.none { entry -> entry.sections.any { it.tone != "plain" } }) return renderMarkdown(markdown)

    return buildString {
        entries.filter { it.sections.isNotEmpty() }.forEach { entry ->
            append("""<div class="entry">""")
            if (entry.time.isNotBlank()) append("""<div class="entry-time">${escape(entry.time)}</div>""")
            entry.sections.sortedBy { it.order }.forEach {
                append("""<section class="sec sec-${it.tone}"><h3>${escape(it.title)}</h3>${renderMarkdown(it.body)}</section>""")
            }
            append("</div>")
        }
    }
}

private fun dayLabel(day: LocalDate) = "$day (${day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)})"

private fun ReviewProposal.toView() = ProposalView(requireNotNull(id), title, status, favorite, reviewDay, renderMarkdown(body))

// 한 번의 일지를 renderJournal 이 읽는 모양("## 시각 KST — 제목" + 본문)으로 바꾼다.
private fun ReviewJournal.toMarkdown() = "## ${createdAt.atZone(KST).format(CLOCK_TIME)} KST — $title\n\n$body"

/**
 * 일일 리뷰 화면: 날짜 목록 → 날짜 하나(그날 일지 + 제안) → 즐겨찾기한 제안.
 * 버튼으로 리뷰를 지금 한 번 돌릴 수도 있다(Claude API 호출 = 비용 발생).
 */
@Controller
class ReviewController(
    private val reviewService: ReviewService,
    private val archive: ReviewArchive,
    // 비어 있으면(로컬) 확인하지 않는다. 서버는 .env 의 REVIEW_RUN_TOKEN 으로 채워 버튼이 유료 호출이라 아무나 못 누르게 한다.
    @Value("\${quantlog.review.run-token:}") private val runToken: String,
) {
    @GetMapping("/review")
    fun days(
        @RequestParam(defaultValue = "$PAGE_STEP") limit: Int,
        model: Model,
    ): String {
        val size = limit.coerceIn(PAGE_STEP, PAGE_MAX)
        val page = archive.days(size)
        model.addAttribute("tokenRequired", runToken.isNotBlank())
        model.addAttribute("days", page.items.map { DayRow(it.day, dayLabel(it.day), it.journals, it.proposals, it.favorites) })
        model.addAttribute("moreLimit", if (page.hasMore) size + PAGE_STEP else null)
        model.addAttribute("favoriteCount", archive.favoriteCount())
        return "review"
    }

    @GetMapping("/review/day/{day}")
    fun day(
        @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) day: LocalDate,
        model: Model,
    ): String {
        val journals = archive.journalsOf(day)
        val proposals = archive.proposalsOf(day)
        if (journals.isEmpty() && proposals.isEmpty()) throw ResponseStatusException(HttpStatus.NOT_FOUND, "그날 기록이 없습니다")
        val neighbors = archive.neighbors(day)
        model.addAttribute("dayLabel", dayLabel(day))
        model.addAttribute("journalHtml", renderJournal(journals.joinToString("\n\n") { it.toMarkdown() }))
        model.addAttribute("hasJournal", journals.isNotEmpty())
        model.addAttribute("proposals", proposals.map { it.toView() })
        model.addAttribute("previousDay", neighbors.previous)
        model.addAttribute("nextDay", neighbors.next)
        model.addAttribute("backTo", "/review/day/$day")
        return "review-day"
    }

    @GetMapping("/review/favorites")
    fun favorites(
        @RequestParam(defaultValue = "$PAGE_STEP") limit: Int,
        model: Model,
    ): String {
        val size = limit.coerceIn(PAGE_STEP, PAGE_MAX)
        val page = archive.favorites(size)
        model.addAttribute("proposals", page.items.map { it.toView() })
        model.addAttribute("moreLimit", if (page.hasMore) size + PAGE_STEP else null)
        model.addAttribute("favoriteCount", archive.favoriteCount())
        model.addAttribute("backTo", "/review/favorites?limit=$size")
        return "review-favorites"
    }

    /** 즐겨찾기를 켜거나 끈 뒤 [backTo] 로 돌아간다. 사이트 밖으로 튕기지 않도록 `/review` 아래 경로만 받는다. */
    @PostMapping("/review/proposals/{id}/favorite")
    fun favorite(
        @PathVariable id: Long,
        @RequestParam favorite: Boolean,
        @RequestParam(defaultValue = "/review") backTo: String,
    ): String {
        if (!archive.setFavorite(id, favorite)) throw ResponseStatusException(HttpStatus.NOT_FOUND, "없는 제안입니다")
        val safe = if (backTo.startsWith("/review") && !backTo.startsWith("//") && '\\' !in backTo) backTo else "/review"
        return "redirect:$safe"
    }

    @PostMapping("/review/run")
    fun run(
        @RequestParam(defaultValue = "") token: String,
        redirect: RedirectAttributes,
    ): String {
        if (runToken.isNotBlank() && !MessageDigest.isEqual(token.toByteArray(), runToken.toByteArray())) {
            redirect.addFlashAttribute("error", "실행 비밀번호가 맞지 않습니다")
            return "redirect:/review"
        }
        runCatching { reviewService.run() }
            .onSuccess { redirect.addFlashAttribute("done", "리뷰를 만들었습니다 (원칙 변경 제안 ${it.proposalIds.size}건)") }
            .onFailure {
                log.error(it) { "[리뷰] 수동 실행 실패" }
                redirect.addFlashAttribute("error", "리뷰 실패: ${it.message}")
            }
        return "redirect:/review"
    }
}

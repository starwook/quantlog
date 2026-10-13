package com.quantlog.review

import com.quantlog.playbook.Playbook
import com.quantlog.playbook.PlaybookNote
import mu.KotlinLogging
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import java.security.MessageDigest

private val log = KotlinLogging.logger {}

// 일지는 하루 한 파일이라 일주일 + 여유, 제안서는 드물게 생기므로 이만큼이면 최근 것이 다 보인다. 더 오래된 건 playbook/ 폴더에서 본다.
private const val JOURNAL_LIMIT = 10
private const val PROPOSAL_LIMIT = 20

/** 화면에 보여줄 노트. [html] 은 마크다운을 HTML 로 바꾼 것(원문의 HTML 태그는 이스케이프). */
data class NoteView(val name: String, val html: String)

private val markdownParser = Parser.builder().build()
private val markdownRenderer = HtmlRenderer.builder().escapeHtml(true).build()

private fun renderMarkdown(markdown: String): String = markdownRenderer.render(markdownParser.parse(markdown))

private fun PlaybookNote.toView() = NoteView(name, renderMarkdown(content))

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

/** 일일 리뷰 화면: 최근 일지·원칙 변경 제안을 보고, 버튼으로 리뷰를 지금 한 번 돌린다(Claude API 호출 = 비용 발생). */
@Controller
class ReviewController(
    private val reviewService: ReviewService,
    private val playbook: Playbook,
    // 비어 있으면(로컬) 확인하지 않는다. 서버는 .env 의 REVIEW_RUN_TOKEN 으로 채워 버튼이 유료 호출이라 아무나 못 누르게 한다.
    @Value("\${quantlog.review.run-token:}") private val runToken: String,
) {
    @GetMapping("/review")
    fun page(model: Model): String {
        model.addAttribute("tokenRequired", runToken.isNotBlank())
        model.addAttribute("journals", playbook.recentJournals(JOURNAL_LIMIT).map { NoteView(it.name, renderJournal(it.content)) })
        model.addAttribute("proposals", playbook.recentProposals(PROPOSAL_LIMIT).map { it.toView() })
        return "review"
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
            .onSuccess { redirect.addFlashAttribute("done", "리뷰를 만들었습니다 (원칙 변경 제안 ${it.proposals.size}건)") }
            .onFailure {
                log.error(it) { "[리뷰] 수동 실행 실패" }
                redirect.addFlashAttribute("error", "리뷰 실패: ${it.message}")
            }
        return "redirect:/review"
    }
}

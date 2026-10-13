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

private fun PlaybookNote.toView() = NoteView(name, markdownRenderer.render(markdownParser.parse(content)))

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
        model.addAttribute("journals", playbook.recentJournals(JOURNAL_LIMIT).map { it.toView() })
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

package com.quantlog.review

import com.quantlog.playbook.Playbook
import mu.KotlinLogging
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.servlet.mvc.support.RedirectAttributes

private val log = KotlinLogging.logger {}

// 일지는 하루 한 파일이라 일주일 + 여유, 제안서는 드물게 생기므로 이만큼이면 최근 것이 다 보인다. 더 오래된 건 playbook/ 폴더에서 본다.
private const val JOURNAL_LIMIT = 10
private const val PROPOSAL_LIMIT = 20

/** 일일 리뷰 화면: 최근 일지·원칙 변경 제안을 보고, 버튼으로 리뷰를 지금 한 번 돌린다(Claude API 호출 = 비용 발생). */
@Controller
class ReviewController(
    private val reviewService: ReviewService,
    private val playbook: Playbook,
) {
    @GetMapping("/review")
    fun page(model: Model): String {
        model.addAttribute("journals", playbook.recentJournals(JOURNAL_LIMIT))
        model.addAttribute("proposals", playbook.recentProposals(PROPOSAL_LIMIT))
        return "review"
    }

    @PostMapping("/review/run")
    fun run(redirect: RedirectAttributes): String {
        runCatching { reviewService.run() }
            .onSuccess { redirect.addFlashAttribute("done", "리뷰를 만들었습니다 (원칙 변경 제안 ${it.proposals.size}건)") }
            .onFailure {
                log.error(it) { "[리뷰] 수동 실행 실패" }
                redirect.addFlashAttribute("error", "리뷰 실패: ${it.message}")
            }
        return "redirect:/review"
    }
}

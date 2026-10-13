package com.quantlog.review

import com.quantlog.playbook.Playbook
import com.quantlog.playbook.PlaybookNote
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Path

@WebMvcTest(ReviewController::class)
class ReviewControllerTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockBean
    lateinit var reviewService: ReviewService

    @MockBean
    lateinit var playbook: Playbook

    @Test
    fun `일지와 제안이 없으면 빈 상태 안내를 보여준다`() {
        `when`(playbook.recentJournals(10)).thenReturn(emptyList())
        `when`(playbook.recentProposals(20)).thenReturn(emptyList())

        mvc.perform(get("/review"))
            .andExpect(status().isOk)
            .andExpect(content().string(org.hamcrest.Matchers.containsString("아직 일지가 없습니다")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("아직 제안이 없습니다")))
    }

    @Test
    fun `최근 일지 내용을 화면에 보여준다`() {
        `when`(playbook.recentJournals(10)).thenReturn(listOf(PlaybookNote("2026-10-13", "오늘 체결 0건")))
        `when`(playbook.recentProposals(20)).thenReturn(emptyList())

        mvc.perform(get("/review"))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("2026-10-13")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("오늘 체결 0건")))
    }

    @Test
    fun `일지는 시각을 맨 위 메타로 빼고 절을 정해진 순서로 보여준다`() {
        val markdown =
            "# 일지 2026-10-13\n\n## 15:31:46 KST — 일일 리뷰\n\n" +
                "## 규칙이 의도대로 동작했나\n- 익절 정상\n\n## 아쉬운 점\n- 늦은 진입\n\n## 한 줄 결론\n체결 2건\n"

        val html = renderJournal(markdown)

        assert(html.contains("""<div class="entry-time">15:31 · 일일 리뷰</div>"""))
        assert(!html.contains("15:31:46"))
        val order = listOf("sec-sum", "sec-bad", "sec-rule").map { html.indexOf(it) }
        assert(order.all { it >= 0 } && order == order.sorted())
    }

    @Test
    fun `알려진 절이 없는 옛 일지는 마크다운 그대로 그린다`() {
        val html = renderJournal("# 일지\n\n그냥 메모\n")

        assert(html.contains("그냥 메모") && !html.contains("sec-"))
    }

    @Test
    fun `실행하면 결과 메시지와 함께 화면으로 돌아간다`() {
        `when`(reviewService.run()).thenReturn(ReviewResult(Path.of("j.md"), listOf(Path.of("p.md"))))

        mvc.perform(post("/review/run"))
            .andExpect(redirectedUrl("/review"))
            .andExpect(flash().attributeExists("done"))
    }

    @Test
    fun `실행이 실패하면 이유를 화면에 알린다`() {
        `when`(reviewService.run()).thenThrow(IllegalStateException("키가 비어 있습니다"))

        mvc.perform(post("/review/run"))
            .andExpect(redirectedUrl("/review"))
            .andExpect(flash().attribute("error", "리뷰 실패: 키가 비어 있습니다"))
    }
}

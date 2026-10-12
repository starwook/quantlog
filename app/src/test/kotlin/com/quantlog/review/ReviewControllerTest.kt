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

package com.quantlog.review

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
import java.time.Instant
import java.time.LocalDate

@WebMvcTest(ReviewController::class)
class ReviewControllerTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockBean
    lateinit var reviewService: ReviewService

    @MockBean
    lateinit var archive: ReviewArchive

    private val day = LocalDate.of(2026, 10, 13)

    private fun proposal(
        id: Long,
        favorite: Boolean = false,
    ) = ReviewProposal(day, "익절 폭 검토", "표본 부족", createdAt = Instant.parse("2026-10-13T06:31:00Z")).also {
        it.javaClass.getDeclaredField("id").apply { isAccessible = true }.set(it, id)
        it.favorite = favorite
    }

    @Test
    fun `일지가 없으면 빈 상태 안내를 보여준다`() {
        `when`(archive.days(20)).thenReturn(Page(emptyList(), false))

        mvc.perform(get("/review"))
            .andExpect(status().isOk)
            .andExpect(content().string(org.hamcrest.Matchers.containsString("아직 일지가 없습니다")))
    }

    @Test
    fun `날짜 목록은 날짜와 건수만 보여주고 더 있으면 더 보기를 건다`() {
        `when`(archive.days(20)).thenReturn(Page(listOf(DaySummary(day, 1, 2, 1)), hasMore = true))
        `when`(archive.favoriteCount()).thenReturn(1)

        mvc.perform(get("/review"))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("2026-10-13 (화)")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("/review/day/2026-10-13")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("/review?limit=40")))
    }

    @Test
    fun `날짜를 열면 그날 일지와 제안을 보여준다`() {
        `when`(archive.journalsOf(day)).thenReturn(
            listOf(ReviewJournal(day, "일일 리뷰", "## 한 줄 결론\n체결 2건", Instant.parse("2026-10-13T06:31:00Z"))),
        )
        `when`(archive.proposalsOf(day)).thenReturn(listOf(proposal(7)))
        `when`(archive.neighbors(day)).thenReturn(DayNeighbors(LocalDate.of(2026, 10, 12), null))

        mvc.perform(get("/review/day/2026-10-13"))
            .andExpect(status().isOk)
            .andExpect(content().string(org.hamcrest.Matchers.containsString("체결 2건")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("15:31")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("익절 폭 검토")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("/review/proposals/7/favorite")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("/review/day/2026-10-12")))
    }

    @Test
    fun `기록이 없는 날은 404 이다`() {
        `when`(archive.journalsOf(day)).thenReturn(emptyList())
        `when`(archive.proposalsOf(day)).thenReturn(emptyList())

        mvc.perform(get("/review/day/2026-10-13")).andExpect(status().isNotFound)
    }

    @Test
    fun `즐겨찾기 화면은 즐겨찾기한 제안과 원본 일지 링크를 보여준다`() {
        `when`(archive.favorites(20)).thenReturn(Page(listOf(proposal(7, favorite = true)), false))
        `when`(archive.favoriteCount()).thenReturn(1)

        mvc.perform(get("/review/favorites"))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("익절 폭 검토")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("즐겨찾기 해제")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("/review/day/2026-10-13")))
    }

    @Test
    fun `즐겨찾기를 바꾸고 원래 화면으로 돌아간다`() {
        `when`(archive.setFavorite(7, true)).thenReturn(true)

        mvc.perform(post("/review/proposals/7/favorite").param("favorite", "true").param("backTo", "/review/day/2026-10-13"))
            .andExpect(redirectedUrl("/review/day/2026-10-13"))
    }

    @Test
    fun `돌아갈 곳이 사이트 밖이면 목록으로 보낸다`() {
        `when`(archive.setFavorite(7, true)).thenReturn(true)

        mvc.perform(post("/review/proposals/7/favorite").param("favorite", "true").param("backTo", "//evil.example"))
            .andExpect(redirectedUrl("/review"))
    }

    @Test
    fun `없는 제안의 즐겨찾기는 404 이다`() {
        `when`(archive.setFavorite(9, true)).thenReturn(false)

        mvc.perform(post("/review/proposals/9/favorite").param("favorite", "true")).andExpect(status().isNotFound)
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
        `when`(reviewService.run()).thenReturn(ReviewResult(1, listOf(2)))

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

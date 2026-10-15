package com.quantlog.news

import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

@WebMvcTest(NewsController::class)
class NewsControllerTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockBean
    lateinit var service: NewsService

    private val at = Instant.parse("2026-10-15T00:12:00Z")
    private val news = NewsItem(NewsKind.NEWS, "코스피 급락", "https://a.com/1", at, summary = "CPI 쇼크", key = "1")
    private val disclosure = NewsItem(NewsKind.DISCLOSURE, "유상증자결정", "https://dart/1", at, summary = "삼성전자", symbol = "005930", key = "2")
    private val sources =
        listOf(NewsSourceResult("네이버 뉴스", listOf(news)), NewsSourceResult("DART 공시", disabled = true))

    @Test
    fun `뉴스와 공시를 시각과 함께 보여 주고 꺼진 소스는 안내한다`() {
        Mockito.`when`(service.feed(false)).thenReturn(NewsFeed(listOf(news, disclosure), sources, at))

        mvc.perform(get("/news"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("코스피 급락")))
            .andExpect(content().string(containsString("10-15 09:12")))
            .andExpect(content().string(containsString("삼성전자 (005930)")))
            .andExpect(content().string(containsString("API 키가 없습니다")))
    }

    @Test
    fun `종류로 거른다`() {
        Mockito.`when`(service.feed(false)).thenReturn(NewsFeed(listOf(news, disclosure), sources, at))

        mvc.perform(get("/news").param("kind", "DISCLOSURE"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("유상증자결정")))
            .andExpect(content().string(not(containsString("코스피 급락"))))
    }
}

package com.quantlog.news

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NewsSourcesTest {
    private val mapper = ObjectMapper()
    private val props = NewsProperties()
    private val clock = Clock.fixed(Instant.parse("2026-10-15T01:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `네이버 뉴스의 태그와 엔티티를 걷고 원문 링크를 쓴다`() {
        val json =
            """{"items":[{"title":"<b>코스피</b> 급락 &quot;CPI&quot;","originallink":"https://a.com/1","link":"https://n.com/1",
            |"description":"외국인 &amp; 기관 <b>매도</b>","pubDate":"Thu, 15 Oct 2026 09:12:00 +0900"}]}
            """.trimMargin()

        val item = NaverNewsSource(props, mapper).parse(mapper.readTree(json)).single()

        assertEquals("코스피 급락 \"CPI\"", item.title)
        assertEquals("외국인 & 기관 매도", item.summary)
        assertEquals("https://a.com/1", item.link)
        assertEquals(Instant.parse("2026-10-15T00:12:00Z"), item.publishedAt)
        assertEquals(NewsKind.NEWS, item.kind)
    }

    @Test
    fun `키가 없으면 소스를 끄고 부르지 않는다`() {
        assertTrue(NaverNewsSource(props, mapper).fetch().disabled)
        assertTrue(DartDisclosureSource(props, mapper, clock).fetch().disabled)
    }

    @Test
    fun `DART 공시는 유가증권·코스닥만 남기고 접수번호로 링크를 만든다`() {
        val json =
            """{"status":"000","list":[
            |{"corp_cls":"Y","corp_name":"삼성전자","stock_code":"005930","report_nm":" 유상증자결정 ","rcept_no":"20261015000123","rcept_dt":"20261015"},
            |{"corp_cls":"N","corp_name":"코넥스사","stock_code":"111111","report_nm":"기타","rcept_no":"20261015000124","rcept_dt":"20261015"}]}
            """.trimMargin()

        val item = DartDisclosureSource(props, mapper, clock).parse(mapper.readTree(json)).single()

        assertEquals("유상증자결정", item.title)
        assertEquals("005930", item.symbol)
        assertEquals("https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20261015000123", item.link)
        assertEquals(Instant.parse("2026-10-14T15:00:00Z"), item.publishedAt)
    }

    @Test
    fun `DART 조회 결과 없음은 빈 목록, 그 밖의 상태는 오류다`() {
        val source = DartDisclosureSource(props, mapper, clock)

        assertTrue(source.parse(mapper.readTree("""{"status":"013","message":"조회된 데이타가 없습니다."}""")).isEmpty())
        assertFailsWith<IllegalStateException> { source.parse(mapper.readTree("""{"status":"010","message":"등록되지 않은 키"}""")) }
    }
}

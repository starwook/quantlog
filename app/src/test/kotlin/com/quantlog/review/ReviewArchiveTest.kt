package com.quantlog.review

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 실제 쿼리(집계·페이지 자르기)를 DB 로 확인한다. 트랜잭션이 끝나면 롤백돼 로컬 DB 에 남지 않는다.
// 로컬 DB 에 진짜 일지가 있어도 섞이지 않게 2099년 날짜만 쓴다(항상 가장 최근이라 목록 맨 앞에 온다).
// spring.profiles.active 를 명시해 application-local.yml(시크릿 파일)을 읽지 않게 한다 — QuantlogApplicationTest 와 같은 이유.
@SpringBootTest(
    properties = [
        "spring.profiles.active=test", "quantlog.exit.enabled=false",
        "quantlog.stockmaster.enabled=false",
    ],
)
@Transactional
class ReviewArchiveTest {
    @Autowired
    lateinit var archive: ReviewArchive

    private val d1 = LocalDate.of(2099, 1, 5)
    private val d2 = LocalDate.of(2099, 1, 6)
    private val d3 = LocalDate.of(2099, 1, 7)

    @Test
    fun `날짜 목록은 최신 날짜부터 일지·제안·즐겨찾기 건수를 세고 더 있으면 알려준다`() {
        archive.saveJournal(d1, "일일 리뷰", "첫날")
        archive.saveJournal(d2, "일일 리뷰", "둘째 날 오전")
        archive.saveJournal(d2, "일일 리뷰", "둘째 날 오후")
        archive.saveProposal(d2, "익절 폭 검토", "근거")
        val fav = archive.saveProposal(d2, "기록 추가", "근거")
        archive.saveProposal(d3, "제안만 있는 날", "일지 없음")
        archive.setFavorite(requireNotNull(fav.id), true)

        val page = archive.days(2)

        assertEquals(listOf(d3, d2), page.items.map { it.day })
        assertEquals(DaySummary(d3, 0, 1, 0), page.items[0])
        assertEquals(DaySummary(d2, 2, 2, 1), page.items[1])
        assertTrue(page.hasMore)
        assertEquals(listOf(d3, d2, d1), archive.days(3).items.map { it.day })
    }

    @Test
    fun `날짜 하나를 열면 그날 것만 만든 순서대로 읽고 앞뒤 날짜를 찾는다`() {
        archive.saveJournal(d1, "일일 리뷰", "첫날")
        archive.saveJournal(d2, "일일 리뷰", "A")
        archive.saveJournal(d2, "일일 리뷰", "B")
        archive.saveProposal(d3, "제안", "본문")

        assertEquals(listOf("A", "B"), archive.journalsOf(d2).map { it.body })
        assertEquals(listOf("제안"), archive.proposalsOf(d3).map { it.title })
        assertEquals(DayNeighbors(d1, d3), archive.neighbors(d2))
        assertNull(archive.neighbors(d3).next)
    }

    @Test
    fun `즐겨찾기는 켜고 끌 수 있고 즐겨찾기 목록은 최신순이다`() {
        val older = archive.saveProposal(d1, "오래된 제안", "본문")
        val newer = archive.saveProposal(d2, "새 제안", "본문")
        val countBefore = archive.favoriteCount()

        assertTrue(archive.setFavorite(requireNotNull(older.id), true))
        assertTrue(archive.setFavorite(requireNotNull(newer.id), true))
        assertTrue(archive.setFavorite(older.id!!, true)) // 이미 켜져 있어도 안전

        assertEquals(countBefore + 2, archive.favoriteCount())
        assertEquals(listOf("새 제안", "오래된 제안"), archive.favorites(10).items.map { it.title }.take(2))
        assertTrue(archive.favorites(1).hasMore)

        archive.setFavorite(newer.id!!, false)
        assertEquals(countBefore + 1, archive.favoriteCount())
        assertNotNull(archive.favorites(10).items.single { it.title == "오래된 제안" })
        assertFalse(archive.setFavorite(-1, true))
    }
}

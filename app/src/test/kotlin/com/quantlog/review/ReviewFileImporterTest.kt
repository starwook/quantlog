package com.quantlog.review

import com.quantlog.playbook.PlaybookProperties
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals

// 옛 파일(playbook/journal, proposals)을 DB 로 옮기는 동작을 임시 폴더로 확인한다. 롤백되므로 로컬 DB 에 남지 않고,
// 2099년 날짜만 써서 진짜 기록과 섞이지 않는다. spring.profiles.active 는 application-local.yml 을 피하려고 명시한다.
@SpringBootTest(
    properties = [
        "spring.profiles.active=test", "quantlog.exit.enabled=false",
        "quantlog.stockmaster.enabled=false",
    ],
)
@Transactional
class ReviewFileImporterTest {
    @TempDir
    lateinit var dir: Path

    @Autowired
    lateinit var journals: ReviewJournalRepository

    @Autowired
    lateinit var proposals: ReviewProposalRepository

    private fun importer() = ReviewFileImporter(PlaybookProperties(dir.toString()), journals, proposals)

    private fun write(
        path: String,
        text: String,
    ) {
        val file = dir.resolve(path)
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
    }

    @Test
    fun `일지 파일은 기록(시각 제목) 단위로 나뉘어 옮겨진다`() {
        write(
            "journal/2099-03-02.md",
            "# 일지 2099-03-02\n\n## 15:31:46 KST — 일일 리뷰\n\n## 한 줄 결론\n체결 2건\n\n## 16:10:00 KST — 일일 리뷰\n\n두 번째 실행\n",
        )

        assertEquals(2, importer().importAll())

        val rows = journals.findAllByDayOrderByCreatedAtAscIdAsc(LocalDate.of(2099, 3, 2))
        assertEquals(listOf("## 한 줄 결론\n체결 2건", "두 번째 실행"), rows.map { it.body })
        assertEquals(Instant.parse("2099-03-02T06:31:46Z"), rows[0].createdAt)
    }

    @Test
    fun `제안 파일은 제목 상태 본문으로 읽고 같은 날짜 이하 가장 최근 일지의 날짜에 붙는다`() {
        write("journal/2099-03-02.md", "# 일지 2099-03-02\n\n## 15:31:46 KST — 일일 리뷰\n\n본문\n")
        // 다음 날 아침에 돌린 리뷰가 낸 제안: 파일 날짜는 3/3 이지만 리뷰 대상일은 3/2 다.
        write(
            "proposals/2099-03-03-0600-익절-폭-검토.md",
            "# 제안: 익절 폭 검토\n\n- 작성: 2099-03-03-0600 KST\n- 상태: 검토 대기\n\n표본이 부족하다.\n근거 2줄\n",
        )

        assertEquals(2, importer().importAll())

        val proposal = proposals.findAllByReviewDayOrderByCreatedAtAscIdAsc(LocalDate.of(2099, 3, 2)).single()
        assertEquals("익절 폭 검토", proposal.title)
        assertEquals("검토 대기", proposal.status)
        assertEquals("표본이 부족하다.\n근거 2줄", proposal.body)
        assertEquals(Instant.parse("2099-03-02T21:00:00Z"), proposal.createdAt)
    }

    @Test
    fun `몇 번을 돌려도 이미 옮긴 것은 다시 옮기지 않는다`() {
        write("journal/2099-03-02.md", "# 일지 2099-03-02\n\n## 15:31:46 KST — 일일 리뷰\n\n본문\n")
        write("proposals/2099-03-02-1531-제안.md", "# 제안: 제안\n\n- 상태: 검토 대기\n\n본문\n")

        assertEquals(2, importer().importAll())
        assertEquals(0, importer().importAll())
    }

    @Test
    fun `폴더가 없으면 아무것도 하지 않는다`() {
        assertEquals(0, importer().importAll())
    }
}

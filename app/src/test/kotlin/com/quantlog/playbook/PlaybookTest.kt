package com.quantlog.playbook

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PlaybookTest {
    @TempDir
    lateinit var dir: Path

    // 2026-09-25 14:30:00 KST
    private val clock = Clock.fixed(Instant.parse("2026-09-25T05:30:00Z"), ZoneId.of("Asia/Seoul"))

    private fun playbook() = Playbook(PlaybookProperties(dir.toString()), clock)

    @Test
    fun `원칙 파일을 읽는다`() {
        Files.writeString(dir.resolve("principles.md"), "# 원칙\n- P-001 롱만")
        assertTrue(playbook().principles().contains("P-001 롱만"))
    }

    @Test
    fun `원칙 파일이 없으면 실패한다`() {
        assertFailsWith<IllegalStateException> { playbook().principles() }
    }

    @Test
    fun `일지는 날짜별 파일에 시각과 함께 덧붙는다`() {
        val pb = playbook()
        val file = pb.writeJournal("장 시작 전 인식", "국채금리가 급등해서 오늘은 쉰다.")
        pb.writeJournal("판단", "NO_TRADE")

        assertEquals(dir.resolve("journal/2026-09-25.md"), file)
        val text = Files.readString(file)
        assertEquals(1, Regex("# 일지 2026-09-25").findAll(text).count())
        assertTrue(text.contains("## 14:30:00 KST — 장 시작 전 인식"))
        assertTrue(text.contains("국채금리가 급등해서 오늘은 쉰다."))
        assertTrue(text.contains("— 판단"))
    }

    @Test
    fun `제안서는 proposals 에 만들어진다`() {
        val file = playbook().propose("익절을 +2%로 올리자!", "반사실 스윕 결과 +2%가 순기대값 +0.3%p 높음")
        assertEquals("2026-09-25-1430-익절을-2-로-올리자.md", file.fileName.toString())
        assertTrue(Files.readString(file).contains("상태: 검토 대기"))
    }
}

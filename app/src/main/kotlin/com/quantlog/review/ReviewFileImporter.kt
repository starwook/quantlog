package com.quantlog.review

import com.quantlog.playbook.PlaybookProperties
import mu.KotlinLogging
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

private val log = KotlinLogging.logger {}

private val KST: ZoneId = ZoneId.of("Asia/Seoul")

// 옛 일지 파일 안의 한 번의 기록: "## 15:31:46 KST — 일일 리뷰"
private val JOURNAL_ENTRY = Regex("""^## (\d{2}):(\d{2}):(\d{2}) KST\s*[—-]\s*(.*)$""")

// 옛 제안 파일 이름: 2026-10-13-1531-제목.md
private val PROPOSAL_FILE = Regex("""^(\d{4}-\d{2}-\d{2})-(\d{2})(\d{2})-(.*)\.md$""")

/**
 * 일지·제안을 DB 로 옮기기 전에 서버의 `playbook/journal/`, `playbook/proposals/` 파일에 쌓인 것을 한 번 DB 로 옮긴다.
 * 앱이 뜰 때마다 돌지만 이미 옮긴 것은 건너뛰므로(`source` 열) 몇 번을 돌려도 같다. 파일은 지우지도 바꾸지도 않는다.
 * 옮긴 것을 확인한 뒤에는 이 클래스를 지워도 된다.
 */
@Component
class ReviewFileImporter(
    properties: PlaybookProperties,
    private val journals: ReviewJournalRepository,
    private val proposals: ReviewProposalRepository,
) : ApplicationRunner {
    private val root: Path = Path.of(properties.dir)

    override fun run(args: ApplicationArguments) {
        runCatching { importAll() }
            .onSuccess { if (it > 0) log.info { "[리뷰] 옛 일지·제안 파일 ${it}건을 DB 로 옮겼다" } }
            .onFailure { log.error(it) { "[리뷰] 옛 일지·제안 파일을 DB 로 옮기지 못했다" } }
    }

    /** 새로 옮긴 건수. 일지를 먼저 옮겨야 제안이 어느 리뷰 날짜에 속하는지 정할 수 있다. */
    fun importAll(): Int = importJournals() + importProposals()

    private fun markdownFiles(dir: String): List<Path> {
        val folder = root.resolve(dir)
        if (!Files.isDirectory(folder)) return emptyList()
        return Files.list(folder).use { files -> files.filter { it.fileName.toString().endsWith(".md") }.sorted().toList() }
    }

    private fun importJournals(): Int {
        var imported = 0
        for (file in markdownFiles("journal")) {
            val day = runCatching { LocalDate.parse(file.fileName.toString().removeSuffix(".md")) }.getOrNull()
            if (day == null) {
                log.warn { "[리뷰] 날짜 이름이 아닌 일지 파일은 건너뜀: $file" }
                continue
            }
            for (entry in splitJournal(day, Files.readString(file))) {
                val source = "journal/${file.fileName}#${entry.time}"
                if (journals.existsBySource(source)) continue
                val createdAt = day.atTime(LocalTime.parse(entry.time)).atZone(KST).toInstant()
                journals.save(ReviewJournal(day, entry.title.ifBlank { "일지" }.take(100), entry.body, createdAt, source))
                imported++
            }
        }
        return imported
    }

    private class JournalEntry(val time: String, val title: String, val body: String)

    // 시각 제목이 없는 손으로 쓴 파일이면 통째로 하나로 본다.
    private fun splitJournal(
        day: LocalDate,
        text: String,
    ): List<JournalEntry> {
        val entries = mutableListOf<JournalEntry>()
        var time: String? = null
        var title = ""
        val body = StringBuilder()

        fun flush() {
            if (time != null && body.isNotBlank()) entries += JournalEntry(time!!, title, body.toString().trim())
            body.clear()
        }
        text.lineSequence().forEach { line ->
            val match = JOURNAL_ENTRY.matchEntire(line.trim())
            when {
                match != null -> {
                    flush()
                    time = "%s:%s:%s".format(match.groupValues[1], match.groupValues[2], match.groupValues[3])
                    title = match.groupValues[4].trim()
                }
                line.startsWith("# 일지 $day") -> Unit
                else -> {
                    if (time == null && line.isNotBlank()) time = "00:00:00"
                    body.appendLine(line)
                }
            }
        }
        flush()
        return entries
    }

    private fun importProposals(): Int {
        var imported = 0
        for (file in markdownFiles("proposals")) {
            val source = "proposals/${file.fileName}"
            if (proposals.existsBySource(source)) continue
            val match = PROPOSAL_FILE.matchEntire(file.fileName.toString())
            if (match == null) {
                log.warn { "[리뷰] 이름 형식이 다른 제안 파일은 건너뜀: $file" }
                continue
            }
            val (date, hour, minute, slug) = match.destructured
            val fileDay = LocalDate.parse(date)
            val createdAt = fileDay.atTime(hour.toInt(), minute.toInt()).atZone(KST).toInstant()
            // 제안은 리뷰가 낸 날(파일 날짜) 또는 그 전 평일 일지에서 나온다 → 그 날짜 이하에서 가장 최근 일지의 날짜에 붙인다.
            val reviewDay = journals.findFirstByDayLessThanEqualOrderByDayDesc(fileDay)?.day ?: fileDay
            val parsed = parseProposal(Files.readString(file), slug.replace('-', ' '))
            proposals.save(ReviewProposal(reviewDay, parsed.title.take(200), parsed.body, parsed.status, createdAt, source))
            imported++
        }
        return imported
    }

    private class ParsedProposal(val title: String, val status: String, val body: String)

    // 파일 모양: "# 제안: 제목\n\n- 작성: ...\n- 상태: 검토 대기\n\n본문"
    private fun parseProposal(
        text: String,
        fallbackTitle: String,
    ): ParsedProposal {
        val lines = text.lines()
        val title = lines.firstOrNull { it.startsWith("# 제안:") }?.removePrefix("# 제안:")?.trim().orEmpty().ifBlank { fallbackTitle }
        val statusIndex = lines.indexOfFirst { it.startsWith("- 상태:") }
        val status = if (statusIndex >= 0) lines[statusIndex].removePrefix("- 상태:").trim() else ""
        val body = if (statusIndex >= 0) lines.drop(statusIndex + 1) else lines.filterNot { it.startsWith("# 제안:") }
        return ParsedProposal(title, status.ifBlank { ReviewProposal.STATUS_PENDING }.take(20), body.joinToString("\n").trim())
    }
}

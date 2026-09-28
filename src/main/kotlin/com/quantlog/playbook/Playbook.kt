package com.quantlog.playbook

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Clock
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@ConfigurationProperties(prefix = "quantlog.playbook")
data class PlaybookProperties(
    val dir: String = "playbook",
)

@Configuration
class PlaybookConfig {
    @Bean
    fun clock(): Clock = Clock.system(ZoneId.of("Asia/Seoul"))
}

/**
 * 봇의 "머릿속" 파일 저장소. 원칙을 읽고, 일지를 쓰고, 원칙 변경안을 제안한다.
 * 원칙 파일 자체(principles.md)는 사람과 토론해서만 바꾸므로 여기서 쓰지 않는다. 구조는 playbook/README.md 참고.
 */
@Component
class Playbook(
    properties: PlaybookProperties,
    private val clock: Clock,
) {
    private val root: Path = Path.of(properties.dir)

    /** 현재 유효한 원칙 전문. 원칙 없이 판단하면 안 되므로 파일이 없으면 실패한다. */
    fun principles(): String {
        val file = root.resolve(PRINCIPLES_FILE)
        check(Files.isRegularFile(file)) { "원칙 파일이 없습니다: ${file.toAbsolutePath()}" }
        return Files.readString(file)
    }

    /** 오늘 일지에 생각 한 토막을 덧붙인다. 파일이 없으면 날짜 제목과 함께 만든다. */
    @Synchronized
    fun writeJournal(
        title: String,
        body: String,
    ): Path {
        val now = clock.instant().atZone(clock.zone)
        val file = root.resolve(JOURNAL_DIR).resolve("${now.toLocalDate()}.md")
        Files.createDirectories(file.parent)
        val header = if (Files.exists(file)) "" else "# 일지 ${now.toLocalDate()}\n"
        val entry = "$header\n## ${now.format(TIME)} KST — $title\n\n${body.trim()}\n"
        Files.writeString(file, entry, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        return file
    }

    /** 원칙 변경 제안서를 proposals/ 에 남긴다. 반영 여부는 사용자와 토론해서 결정한다. */
    fun propose(
        title: String,
        body: String,
    ): Path {
        val now = clock.instant().atZone(clock.zone)
        val slug = title.trim().replace(Regex("[^0-9A-Za-z가-힣]+"), "-").trim('-').take(60).ifEmpty { "proposal" }
        val file = root.resolve(PROPOSALS_DIR).resolve("${now.format(STAMP)}-$slug.md")
        Files.createDirectories(file.parent)
        Files.writeString(
            file,
            "# 제안: ${title.trim()}\n\n- 작성: ${now.format(STAMP)} KST\n- 상태: 검토 대기\n\n${body.trim()}\n",
            StandardOpenOption.CREATE_NEW,
        )
        return file
    }

    private companion object {
        const val PRINCIPLES_FILE = "principles.md"
        const val JOURNAL_DIR = "journal"
        const val PROPOSALS_DIR = "proposals"
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm")
    }
}

package com.quantlog.playbook

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path

@ConfigurationProperties(prefix = "quantlog.playbook")
data class PlaybookProperties(
    val dir: String = "playbook",
)

/**
 * 봇의 "머릿속" 파일 저장소. 지금은 원칙(principles.md)을 읽는 일만 한다 — 사람과 토론해서만 바꾸므로 여기서 쓰지 않는다.
 * 일지·원칙 변경 제안은 DB 로 옮겼다(`review/ReviewArchive.kt`). 구조는 playbook/README.md 참고.
 */
@Component
class Playbook(
    properties: PlaybookProperties,
) {
    private val root: Path = Path.of(properties.dir)

    /** 현재 유효한 원칙 전문. 원칙 없이 판단하면 안 되므로 파일이 없으면 실패한다. */
    fun principles(): String {
        val file = root.resolve(PRINCIPLES_FILE)
        check(Files.isRegularFile(file)) { "원칙 파일이 없습니다: ${file.toAbsolutePath()}" }
        return Files.readString(file)
    }

    private companion object {
        const val PRINCIPLES_FILE = "principles.md"
    }
}

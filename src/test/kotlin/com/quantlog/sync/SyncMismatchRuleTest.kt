package com.quantlog.sync

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue

/**
 * "외부 API 와 DB 를 맞추는 로직은 어긋날 때마다 [SyncMismatchReporter] 로 보고한다"는 규칙(CLAUDE.md)이 새 기능에도 이어지게 지키는 테스트다.
 * 이름에 Sync/Reconcil 이 들어간 클래스 파일은 보고 창구를 쓰거나, 쓸 필요가 없는 이유를 [OPT_OUT] 로 적어야 한다.
 * 이름으로 찾는 방식이라 이름에 안 들어간 동기화 로직까지 잡지는 못한다 — 그건 CLAUDE.md 규칙과 리뷰가 맡는다.
 */
class SyncMismatchRuleTest {
    private val sourceRoot: Path = Path.of("src/main/kotlin")

    private fun syncFiles(): List<Path> =
        Files.walk(sourceRoot).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }
                .filter { path -> NAME_HINTS.any { path.fileName.toString().contains(it) } }
                .toList()
        }

    @Test
    fun `동기화·재확인 클래스는 불일치 보고를 쓰거나 안 쓰는 이유를 적는다`() {
        val files = syncFiles()
        assertTrue(files.isNotEmpty(), "동기화 클래스를 하나도 못 찾았다 — 이 테스트의 경로나 이름 규칙이 깨졌다")

        val missing =
            files.filter { path ->
                val text = Files.readString(path)
                USES.none { it in text } && OPT_OUT !in text
            }

        assertTrue(
            missing.isEmpty(),
            "다음 파일은 외부(증권사)와 DB 를 맞추는 로직으로 보이는데 불일치 보고가 없다: ${missing.joinToString { it.fileName.toString() }}\n" +
                "어긋날 때마다 SyncMismatchReporter.report(...) 를 부르거나, 비교를 다른 클래스가 한다면 파일에 `$OPT_OUT: 이유` 를 적어라 (CLAUDE.md \"동기화 불일치 보고\").",
        )
    }

    private companion object {
        val NAME_HINTS = listOf("Sync", "Reconcil")

        /** 보고 창구 자체와, 그걸 한 번 감싼 공용 보고 함수. */
        val USES = listOf("SyncMismatchReporter", "reportFillCorrection")
        const val OPT_OUT = "동기화 불일치 보고 불필요"
    }
}

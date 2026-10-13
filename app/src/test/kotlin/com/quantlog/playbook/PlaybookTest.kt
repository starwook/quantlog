package com.quantlog.playbook

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PlaybookTest {
    @TempDir
    lateinit var dir: Path

    private fun playbook() = Playbook(PlaybookProperties(dir.toString()))

    @Test
    fun `원칙 파일을 읽는다`() {
        Files.writeString(dir.resolve("principles.md"), "# 원칙\n- P-001 롱만")
        assertTrue(playbook().principles().contains("P-001 롱만"))
    }

    @Test
    fun `원칙 파일이 없으면 실패한다`() {
        assertFailsWith<IllegalStateException> { playbook().principles() }
    }
}

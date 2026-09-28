package com.quantlog

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.test.assertEquals

/**
 * 프로젝트 루트의 application-local.yml (README 참고, .gitignore 대상) 이 실제로 자동 적용되는지 확인한다.
 * IntelliJ Run Configuration 의 환경변수 없이, 파일만 두면 값이 들어간다는 것을 보장하는 테스트.
 */
class ApplicationLocalConfigTest {
    private val localFile = Path.of("application-local.yml")

    @AfterEach
    fun cleanUp() {
        localFile.deleteIfExists()
    }

    @Test
    fun `프로젝트 루트의 application-local yml 값이 자동으로 적용된다`() {
        Files.writeString(localFile, "kis:\n  mock:\n    app-key: \"local-file-test-key\"\n")

        val context =
            SpringApplicationBuilder(QuantlogApplication::class.java)
                .web(WebApplicationType.NONE)
                .run()
        try {
            assertEquals("local-file-test-key", context.environment.getProperty("kis.mock.app-key"))
        } finally {
            context.close()
        }
    }
}

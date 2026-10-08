package com.quantlog.sync

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncMismatchReporterTest {
    @Test
    fun `불일치는 예외 없이 태그가 붙은 ERROR 로그 한 줄로 남는다`() {
        MismatchLog().use { captured ->
            SyncMismatchReporter.report("잔고 동기화", "삼성전자(005930)", "2주", "5주", "DB 를 증권사 값으로 고침")

            val message = captured.messages.single()
            assertTrue(message.startsWith("[동기화 불일치] 잔고 동기화 삼성전자(005930)"))
            assertTrue("DB=2주" in message && "증권사=5주" in message && "DB 를 증권사 값으로 고침" in message)
        }
    }

    @Test
    fun `태그가 오류 기록의 분류가 된다`() {
        val record =
            com.quantlog.errorlog.LogRecord(
                at = java.time.Instant.now(),
                level = "ERROR",
                logger = "com.quantlog.sync.SyncMismatchReporterKt",
                message = "[동기화 불일치] 잔고 동기화 삼성전자(005930): DB=2주 / 증권사=5주 — DB 를 증권사 값으로 고침",
            )

        assertEquals("동기화 불일치", com.quantlog.errorlog.ErrorLogClassifier.category(record))
    }
}

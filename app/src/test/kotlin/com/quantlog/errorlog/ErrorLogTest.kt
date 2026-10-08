package com.quantlog.errorlog

import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

private fun record(
    message: String,
    logger: String = "com.quantlog.trading.ExitService",
    exceptionClass: String? = null,
) = LogRecord(Instant.parse("2026-10-07T08:00:00Z"), "WARN", logger, message, exceptionClass)

class ErrorLogClassifierTest {
    @Test
    fun `메시지 앞의 대괄호 태그가 분류가 된다`() {
        assertEquals("청산 감시", ErrorLogClassifier.category(record("[청산 감시] 실패: KR 005930")))
    }

    @Test
    fun `태그가 없으면 로거 클래스 이름이 분류가 된다`() {
        assertEquals("ExitService", ErrorLogClassifier.category(record("뭔가 실패")))
    }

    @Test
    fun `숫자만 다른 메시지는 같은 종류로 묶인다`() {
        val a = record("[KIS 느린 호출] /uapi/x 대기 2880ms + 응답 129ms")
        val b = record("[KIS 느린 호출] /uapi/x 대기 3216ms + 응답 11ms")
        assertEquals(ErrorLogClassifier.fingerprint(a), ErrorLogClassifier.fingerprint(b))
    }

    @Test
    fun `종목이나 예외 종류가 다르면 다른 종류다`() {
        val base = record("[청산 감시] 실패: KR 005930")
        assertNotEquals(ErrorLogClassifier.fingerprint(base), ErrorLogClassifier.fingerprint(record("[청산 감시] 실패: KR 삼성")))
        assertNotEquals(ErrorLogClassifier.fingerprint(base), ErrorLogClassifier.fingerprint(base.copy(exceptionClass = "KisApiException")))
    }
}

class ErrorLogRecorderTest {
    private val repository = Mockito.mock(ErrorLogRepository::class.java)
    private val recorder = ErrorLogRecorder(repository)

    @Test
    fun `처음 보는 오류는 새 행으로 저장한다`() {
        recorder.record(record("[청산 감시] 실패: KR 005930"))

        val saved = ArgumentCaptor.forClass(ErrorLog::class.java)
        Mockito.verify(repository).save(saved.capture())
        assertEquals("청산 감시", saved.value.category)
        assertEquals(1, saved.value.count)
    }

    @Test
    fun `같은 종류가 또 나면 횟수와 마지막 발생 시각만 올린다`() {
        val first = record("[청산 감시] 실패: KR 005930")
        val existing =
            ErrorLog(
                ErrorLogClassifier.fingerprint(first),
                "WARN",
                "청산 감시",
                first.logger,
                first.message,
                firstSeen = first.at,
                lastSeen = first.at,
            )
        Mockito.`when`(repository.findByFingerprint(ErrorLogClassifier.fingerprint(first))).thenReturn(existing)

        val later = first.copy(at = first.at.plusSeconds(3))
        recorder.record(later)

        assertEquals(2, existing.count)
        assertEquals(later.at, existing.lastSeen)
        assertEquals(first.at, existing.firstSeen)
        Mockito.verify(repository, Mockito.never()).save(any(ErrorLog::class.java))
    }
}

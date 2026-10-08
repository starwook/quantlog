package com.quantlog.errorlog

import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
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

@WebMvcTest(ErrorLogController::class)
class ErrorLogControllerTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockBean
    lateinit var repository: ErrorLogRepository

    @Test
    fun `오류 기록을 분류와 횟수와 함께 그린다`() {
        val at = Instant.now()
        val log =
            ErrorLog(
                "fp",
                "WARN",
                "청산 감시",
                "com.quantlog.trading.ExitService",
                "[청산 감시] 실패: KR 005930",
                firstSeen = at,
                lastSeen = at,
                count = 1234,
            )
        Mockito.`when`(repository.findAllByLastSeenAfterOrderByLastSeenDesc(any(Instant::class.java) ?: at)).thenReturn(listOf(log))

        mvc.perform(get("/errors"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("[청산 감시] 실패: KR 005930")))
            .andExpect(content().string(containsString("1,234회")))
            .andExpect(content().string(containsString("청산 감시 1")))
    }

    @Test
    fun `기록이 없어도 화면은 열린다`() {
        mvc.perform(get("/errors").param("level", "ERROR").param("days", "1"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("오류 기록이 없습니다")))
    }
}

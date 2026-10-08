package com.quantlog.errorlog

import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
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

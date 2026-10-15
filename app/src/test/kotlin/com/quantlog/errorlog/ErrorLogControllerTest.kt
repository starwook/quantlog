package com.quantlog.errorlog

import com.quantlog.gatewayclient.GatewayStart
import com.quantlog.gatewayclient.GatewayStartRepository
import com.quantlog.sync.SyncEvent
import com.quantlog.sync.SyncEventKind
import com.quantlog.sync.SyncEventRepository
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

    @MockBean
    lateinit var gatewayStarts: GatewayStartRepository

    @MockBean
    lateinit var syncEvents: SyncEventRepository

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
    fun `게이트웨이가 새로 뜬 시각을 한국 시간으로 보여준다`() {
        val start =
            GatewayStart(
                "g2",
                Instant.parse("2026-10-13T21:20:48Z"),
                Instant.parse("2026-10-13T21:20:31Z"),
                Instant.parse("2026-10-13T21:20:50Z"),
            )
        Mockito.`when`(gatewayStarts.findAllByStartedAtAfterOrderByStartedAtDesc(any(Instant::class.java) ?: Instant.now()))
            .thenReturn(listOf(start))

        mvc.perform(get("/errors"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("10-14 06:20:48 시작")))
            .andExpect(content().string(containsString("10-14 06:20:31")))
    }

    @Test
    fun `기록이 없어도 화면은 열린다`() {
        mvc.perform(get("/errors").param("level", "ERROR").param("days", "1"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("오류 기록이 없습니다")))
    }

    @Test
    fun `레벨을 바꿨을 때 그 레벨에 없는 분류가 남아 있어도 비어 보이지 않는다`() {
        val at = Instant.now()
        val warn = ErrorLog("w", "WARN", "KIS 한도 초과", "c.W", "한도 초과", firstSeen = at, lastSeen = at, count = 3)
        val error = ErrorLog("e", "ERROR", "동기화 불일치", "c.E", "[동기화 불일치] 수량 다름", firstSeen = at, lastSeen = at, count = 2)
        Mockito.`when`(repository.findAllByLastSeenAfterOrderByLastSeenDesc(any(Instant::class.java) ?: at)).thenReturn(listOf(warn, error))

        mvc.perform(get("/errors").param("level", "ERROR").param("category", "KIS 한도 초과"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("[동기화 불일치] 수량 다름")))
            .andExpect(content().string(containsString("1건")))
    }

    @Test
    fun `동기화·연결 사건을 한 건씩 한국 시간으로 보여준다`() {
        val event = SyncEvent(Instant.parse("2026-10-14T02:00:02Z"), SyncEventKind.HALT.name, "한투 체결통보 끊김", "게이트웨이가 알림")
        Mockito.`when`(
            syncEvents.findTop300ByOccurredAtAfterOrderByOccurredAtDesc(any(Instant::class.java) ?: Instant.EPOCH),
        ).thenReturn(listOf(event))

        mvc.perform(get("/errors"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("동기화·연결 기록")))
            .andExpect(content().string(containsString("거래 정지")))
            .andExpect(content().string(containsString("10-14 11:00:02")))
    }
}

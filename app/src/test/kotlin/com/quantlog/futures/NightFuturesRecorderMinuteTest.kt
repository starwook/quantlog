package com.quantlog.futures

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.LocalTime

class NightFuturesRecorderMinuteTest {
    private fun minute(
        tick: String,
        now: String,
    ) = NightFuturesRecorder.minuteOf(LocalTime.parse(tick), LocalDateTime.parse(now))

    @Test
    fun `23시 이후 체결도 오늘 봉이다`() {
        assertThat(minute("23:00:05", "2026-10-15T23:00:06")).isEqualTo(LocalDateTime.parse("2026-10-15T23:00:00"))
        assertThat(minute("23:59:59", "2026-10-15T23:59:59")).isEqualTo(LocalDateTime.parse("2026-10-15T23:59:00"))
    }

    @Test
    fun `자정을 막 넘겨 받은 직전 체결은 전날 봉이다`() {
        assertThat(minute("23:59:59", "2026-10-16T00:00:01")).isEqualTo(LocalDateTime.parse("2026-10-15T23:59:00"))
    }

    @Test
    fun `새벽 체결은 오늘 봉이다`() {
        assertThat(minute("02:10:00", "2026-10-16T02:10:01")).isEqualTo(LocalDateTime.parse("2026-10-16T02:10:00"))
    }
}

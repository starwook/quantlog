package com.quantlog.trading

import com.quantlog.broker.RealtimePriceFeed
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import kotlin.test.assertEquals

class ExitSchedulerTest {
    private val feed = Mockito.mock(RealtimePriceFeed::class.java)

    @Test
    fun `켜져 있으면 전체 폴링을 실행한다`() {
        val service = Mockito.mock(ExitService::class.java)
        ExitScheduler(service, feed, ExitProperties(enabled = true)).run()
        assertEquals(listOf("checkAll"), Mockito.mockingDetails(service).invocations.map { it.method.name })
    }

    @Test
    fun `꺼져 있으면 아무것도 안 한다`() {
        val service = Mockito.mock(ExitService::class.java)
        ExitScheduler(service, feed, ExitProperties(enabled = false)).run()
        assertEquals(0, Mockito.mockingDetails(service).invocations.size)
    }
}

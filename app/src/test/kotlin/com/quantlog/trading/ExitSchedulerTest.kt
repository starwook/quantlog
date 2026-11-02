package com.quantlog.trading

import org.junit.jupiter.api.Test
import org.mockito.Mockito
import kotlin.test.assertEquals

class ExitSchedulerTest {
    @Test
    fun `켜져 있으면 전체 폴링을 실행한다`() {
        val service = Mockito.mock(ExitService::class.java)
        ExitScheduler(service, ExitProperties(enabled = true)).run()
        assertEquals(listOf("expirePending", "checkAll"), Mockito.mockingDetails(service).invocations.map { it.method.name })
    }

    @Test
    fun `꺼져 있으면 아무것도 안 한다`() {
        val service = Mockito.mock(ExitService::class.java)
        ExitScheduler(service, ExitProperties(enabled = false)).run()
        assertEquals(0, Mockito.mockingDetails(service).invocations.size)
    }
}

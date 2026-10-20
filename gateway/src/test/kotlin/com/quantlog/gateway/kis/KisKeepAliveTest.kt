package com.quantlog.gateway.kis

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KisKeepAliveTest {
    @Test
    fun `pong 이나 메시지가 오면 계속 ping 만 보낸다`() {
        val keepAlive = KisKeepAlive()
        repeat(3) {
            assertFalse(keepAlive.tick())
            keepAlive.onAlive()
        }
    }

    @Test
    fun `ping 뒤 다음 주기까지 아무것도 안 오면 죽은 연결로 본다`() {
        val keepAlive = KisKeepAlive()
        assertFalse(keepAlive.tick())
        assertTrue(keepAlive.tick())
    }

    @Test
    fun `새 연결이 붙으면 이전 연결의 대기는 버린다`() {
        val keepAlive = KisKeepAlive()
        keepAlive.tick()
        keepAlive.reset()
        assertFalse(keepAlive.tick())
    }
}

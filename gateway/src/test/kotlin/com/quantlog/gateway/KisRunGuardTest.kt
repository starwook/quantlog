package com.quantlog.gateway

import com.quantlog.gateway.kis.KisProperties
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

class KisRunGuardTest {
    private val withKeys = KisProperties(appKey = "k", appSecret = "s", account = "12345678-01")

    @Test
    fun `키가 있어도 서버 허락이 없으면 시작을 거부한다`() {
        assertFailsWith<KisRunNotAllowedException> { KisRunGuard(withKeys, GatewayProperties()).check() }
    }

    @Test
    fun `서버 허락이 있거나 키가 없으면 통과한다`() {
        KisRunGuard(withKeys, GatewayProperties(allowKis = true)).check()
        KisRunGuard(KisProperties(), GatewayProperties()).check()
    }
}

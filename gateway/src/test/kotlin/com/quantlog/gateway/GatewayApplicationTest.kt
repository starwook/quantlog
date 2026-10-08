package com.quantlog.gateway

import com.quantlog.gateway.lease.LeaseService
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

// spring.profiles.active 를 명시해 프로젝트 루트의 application-local.yml(개발자 로컬 시크릿 파일)을 이 테스트가 절대 읽지 않게 한다.
// 증권사 키가 없으니 KIS 호출은 나가지 않고, 주기 수집은 꺼서 테스트 중 외부 호출이 없다.
@SpringBootTest(
    properties = [
        "spring.profiles.active=test", "quantlog.broker.type=paper", "quantlog.error-log.enabled=false",
        "quantlog.gateway.candle-collection.enabled=false", "quantlog.gateway.balance.enabled=false",
        "quantlog.gateway.heartbeat-millis=600000",
    ],
)
class GatewayApplicationTest {
    @Autowired
    lateinit var lease: LeaseService

    @Test
    fun `컨텍스트가 시크릿 없이도 뜨고 단일 실행 잠금을 잡는다`() {
        assertTrue(lease.instanceId.isNotBlank())
    }
}

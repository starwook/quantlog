package com.quantlog.gateway.record

import org.junit.jupiter.api.Test
import org.springframework.transaction.annotation.Transactional
import kotlin.test.assertNotNull

class KisMinuteChartRepositoryTest {
    // CandleCollector.prune 은 트랜잭션 밖에서 부르므로, 삭제 쿼리 자체가 트랜잭션을 열어야 한다(2026-10-14: 없어서 매번 경고만 나고 못 지움).
    @Test
    fun `오래된 분봉 원문 삭제는 스스로 트랜잭션을 연다`() {
        val method = KisMinuteChartRepository::class.java.getMethod("deleteByReceivedAtBefore", java.time.Instant::class.java)

        assertNotNull(method.getAnnotation(Transactional::class.java))
    }
}

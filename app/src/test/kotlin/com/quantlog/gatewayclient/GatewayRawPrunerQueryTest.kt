package com.quantlog.gatewayclient

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import kotlin.test.assertTrue

// 삭제 쿼리(@Modifying + @Immutable 엔티티)가 실제 DB 에서 도는지 확인한다. 트랜잭션이 끝나면 롤백돼 로컬 DB 에 남지 않는다.
// spring.profiles.active 를 명시해 application-local.yml(시크릿 파일)을 읽지 않게 한다 — QuantlogApplicationTest 와 같은 이유.
@SpringBootTest(
    properties = [
        "spring.profiles.active=test", "quantlog.exit.enabled=false",
        "quantlog.stockmaster.enabled=false",
    ],
)
@Transactional
class GatewayRawPrunerQueryTest {
    @Autowired
    lateinit var charts: KisMinuteChartRowRepository

    @Autowired
    lateinit var balances: KisBalanceRowRepository

    @Autowired
    lateinit var logs: GatewayLogRowRepository

    @Test
    fun `삭제 쿼리 셋이 DB 에서 실행된다`() {
        val old = Instant.parse("2020-01-01T00:00:00Z")
        val saved = charts.save(KisMinuteChartRow(market = "KR", symbol = "ZQ0001", receivedAt = old, body = "{}"))
        val balance = balances.save(KisBalanceRow(fetchedAt = old, receivedAt = old, body = "{}"))
        val logRow = logs.save(GatewayLogRow(loggedAt = old, level = "WARN", logger = "t", message = "m"))

        assertTrue(charts.deleteReadBefore(Instant.parse("2020-01-02T00:00:00Z"), saved.id!!) >= 1)
        assertTrue(balances.deleteOlderBefore(Instant.parse("2020-01-02T00:00:00Z"), balance.id!! + 1) >= 1)
        assertTrue(logs.deleteReadBefore(Instant.parse("2020-01-02T00:00:00Z"), logRow.id!!) >= 1)
    }
}

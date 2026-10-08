package com.quantlog.stockmaster

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.PageRequest
import org.springframework.transaction.annotation.Transactional
import kotlin.test.assertEquals

// 실제 쿼리(정렬 포함)를 DB 로 확인한다. 트랜잭션이 끝나면 롤백돼 로컬 DB 에 남지 않고, 'ZQ' 가 들어간 이름만 조회해 기존 데이터와 섞이지 않는다.
// spring.profiles.active 를 명시해 application-local.yml(시크릿 파일)을 읽지 않게 한다 — QuantlogApplicationTest 와 같은 이유.
@SpringBootTest(
    properties = [
        "spring.profiles.active=test", "quantlog.exit.enabled=false", "quantlog.reconcile.enabled=false",
        "quantlog.stockmaster.enabled=false",
    ],
)
@Transactional
class StockMasterSearchOrderTest {
    @Autowired
    lateinit var repository: StockMasterRepository

    private fun stock(
        code: String,
        name: String,
        group: String,
    ) = StockMaster(code, "KR7$code", name, MasterExchange.KOSPI, group, tradingSuspended = false, managedIssue = false)

    @Test
    fun `이름 검색은 주권을 ETF 보다, 입력어로 시작하는 이름을 중간에 낀 이름보다 먼저 보여준다`() {
        repository.saveAll(
            listOf(
                stock("ZQ0001", "ACE ZQ그룹", "EF"),
                stock("ZQ0002", "ZQ전자", "ST"),
                stock("ZQ0003", "ZQ전자우", "ST"),
                stock("ZQ0004", "미니 ZQ", "ST"),
                stock("ZQ0005", "ZQ선물 ETF", "EF"),
            ),
        )

        val result = repository.searchByNameContaining("ZQ", PageRequest.of(0, 20)).map { it.shortCode }

        assertEquals(listOf("ZQ0002", "ZQ0003", "ZQ0004", "ZQ0005", "ZQ0001"), result)
    }
}

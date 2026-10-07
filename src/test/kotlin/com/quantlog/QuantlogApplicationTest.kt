package com.quantlog

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest

// spring.profiles.active 를 명시해 프로젝트 루트의 application-local.yml(개발자 로컬 시크릿 파일,
// 기본 프로파일 "local"일 때만 자동 적용됨)을 이 테스트가 절대 읽지 않게 한다.
// 그 파일 내용에 따라 테스트 결과가 달라지면 안 된다 — 과거에 이 파일을 건드리는 테스트가 있었다가
// 개발자의 실제 키 파일을 덮어쓰는 사고가 있었다 (README 참고). 새 테스트를 추가할 때도 이 파일 경로는 건드리지 않는다.
// 청산 스케줄러·체결가 재확인 배치는 꺼서, 테스트 중에 증권사 호출·주문이 나가지 않게 한다.
@SpringBootTest(
    properties = [
        "spring.profiles.active=test", "quantlog.exit.enabled=false", "quantlog.reconcile.enabled=false",
        "quantlog.stockmaster.enabled=false",
    ],
)
class QuantlogApplicationTest {
    @Test
    fun `컨텍스트가 시크릿 없이도 뜬다`() = Unit
}

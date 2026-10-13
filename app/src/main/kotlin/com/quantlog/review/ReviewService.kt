package com.quantlog.review

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.quantlog.playbook.Playbook
import com.quantlog.position.PortfolioService
import org.springframework.stereotype.Service
import java.nio.file.Path
import java.time.Clock

data class ReviewProposal(val title: String, val body: String)

data class ReviewOutput(val journal: String, val proposals: List<ReviewProposal> = emptyList())

data class ReviewResult(val journal: Path, val proposals: List<Path>)

/**
 * 학습 루프의 "리뷰 세션"(기획서 6.4): 코드가 계산한 사실([ReviewFacts])과 현재 원칙을 Claude 에게 주고,
 * 해석을 일지(journal/)와 원칙 변경 제안(proposals/)으로 남긴다. **원칙 파일은 건드리지 않는다** — 반영은 사용자와 토론해서 정한다.
 */
@Service
class ReviewService(
    private val portfolioService: PortfolioService,
    private val playbook: Playbook,
    private val llm: LlmClient,
    private val properties: ReviewProperties,
    private val clock: Clock,
    private val objectMapper: ObjectMapper,
) {
    fun run(): ReviewResult {
        val today = clock.instant().atZone(clock.zone).toLocalDate()
        val facts = ReviewFacts.of(portfolioService.snapshot(), today, properties.days)
        val answer = llm.complete(SYSTEM, "## 현재 원칙\n\n${playbook.principles()}\n\n# 사실 (코드가 계산한 숫자)\n\n$facts")

        val output = parse(answer)
        val journal = playbook.writeJournal("일일 리뷰", output.journal)
        return ReviewResult(journal, output.proposals.map { playbook.propose(it.title, it.body) })
    }

    // 모델이 JSON 을 코드 펜스로 감싸 줄 수 있어 첫 `{` ~ 마지막 `}` 만 잘라 읽는다. 못 읽으면 원문을 일지에 그대로 남긴다(제안은 없음).
    private fun parse(answer: String): ReviewOutput =
        runCatching {
            objectMapper.readValue<ReviewOutput>(answer.substring(answer.indexOf('{'), answer.lastIndexOf('}') + 1))
        }.getOrElse { ReviewOutput(journal = "(응답 형식을 읽지 못해 원문을 그대로 남긴다)\n\n$answer") }

    private companion object {
        val SYSTEM =
            """
            당신은 데이트레이딩 봇의 일일 리뷰어다. 주어진 '현재 원칙'과 '사실'만 보고, 오늘 봇의 매매를 사람 트레이더의 복기처럼 해석한다.

            규칙:
            - 사실에 없는 숫자·뉴스·시장 상황을 지어내지 않는다. 모르면 "데이터 없음"이라고 쓴다.
            - 원칙을 직접 바꾸지 않는다. 바꿀 만한 근거가 있으면 proposals 에 제안으로만 쓴다.
            - 체결 표본이 50건 미만이면 원칙을 뒤집는 제안을 하지 않는다. 관찰 사항으로 일지에만 쓴다.
            - 리스크 가드(주문가능금액, 킬스위치)·롱 온리는 제안 대상이 아니다.
            - 한 번에 하나만 바꾸자고 제안한다(원인 분리). 제안에는 근거가 된 사실을 인용한다.

            반드시 아래 JSON 하나만 출력한다(설명·코드 펜스 금지):
            {"journal": "마크다운. 오늘 무슨 일이 있었고, 잘된 점·못된 점, 규칙이 의도대로 동작했는지", "proposals": [{"title": "짧은 제목", "body": "마크다운. 무엇을 왜 어떻게 바꾸자는지 + 근거"}]}
            제안할 게 없으면 proposals 는 빈 배열이다.
            """.trimIndent()
    }
}

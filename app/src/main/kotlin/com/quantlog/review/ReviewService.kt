package com.quantlog.review

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.quantlog.playbook.Playbook
import com.quantlog.position.PortfolioService
import org.springframework.stereotype.Service
import java.nio.file.Path
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

data class ReviewProposal(val title: String, val body: String)

data class ReviewOutput(val journal: String, val proposals: List<ReviewProposal> = emptyList())

data class ReviewResult(val journal: Path, val proposals: List<Path>)

/** 국내 장 마감 뒤 리뷰가 도는 시각(KST). 스케줄러와 [reviewDay] 가 같은 값을 쓴다. */
val REVIEW_TIME: LocalTime = LocalTime.of(15, 31)

/**
 * 리뷰 대상일 = 마지막으로 장이 마감된 평일. 평일 [REVIEW_TIME] 이후면 오늘, 그 전(새벽·장중)이나 주말이면 직전 평일이다.
 * (공휴일은 따로 보지 않는다 — 그날은 체결이 없다고 나올 뿐이다.)
 */
fun reviewDay(now: ZonedDateTime): LocalDate {
    var day = now.toLocalDate()
    if (now.toLocalTime() < REVIEW_TIME) day = day.minusDays(1)
    while (day.dayOfWeek == DayOfWeek.SATURDAY || day.dayOfWeek == DayOfWeek.SUNDAY) day = day.minusDays(1)
    return day
}

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
        val day = reviewDay(clock.instant().atZone(clock.zone))
        val facts = ReviewFacts.of(portfolioService.snapshot(), day, properties.days)
        val answer = llm.complete(SYSTEM, "## 현재 원칙\n\n${playbook.principles()}\n\n# 사실 (코드가 계산한 숫자)\n\n$facts")

        val output = parse(answer)
        val journal = playbook.writeJournal("일일 리뷰", output.journal, day)
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
            당신은 데이트레이딩 봇의 일일 리뷰어다. 주어진 '현재 원칙'과 '사실'만 보고, '리뷰 대상일' 하루의 봇 매매를 사람 트레이더의 복기처럼 해석한다.

            규칙:
            - 사실에 없는 숫자·뉴스·시장 상황·요일을 지어내지 않는다. 날짜와 요일은 사실에 적힌 그대로 쓴다. 모르면 "데이터 없음"이라고 쓴다.
            - 원칙을 직접 바꾸지 않는다. 바꿀 만한 근거가 있으면 proposals 에 제안으로만 쓴다.
            - 체결 표본이 50건 미만이면 원칙의 수치·규칙을 뒤집는 제안은 하지 않는다. 그 관찰은 일지에만 쓴다.
            - 단, **리뷰하는 데 필요한데 사실에 없는 데이터**(예: 매도 건별 사유, 보유 중 가격 경로)는 표본 수와 상관없이 proposals 에 "무엇을 기록하면 좋은지"로 제안한다. 일지에 "필요하다"고만 쓰고 끝내지 않는다.
            - 리스크 가드(시장별 자본 배분, 킬스위치)·롱 온리는 제안 대상이 아니다.
            - 한 번에 하나만 바꾸자고 제안한다(원인 분리). 제안에는 근거가 된 사실을 인용한다.

            journal 은 사람이 훑어 읽는 글이다. 전문용어 없이 짧은 문장으로, 전체 25줄 이내, 아래 형식을 지킨다.
            ## 한 줄 결론
            (체결 건수·실현손익을 넣은 한 문장. 체결이 없으면 없다고만 쓰고 나머지 절은 생략한다)
            ## 잘된 점
            - (최대 3개)
            ## 아쉬운 점
            - (최대 3개)
            ## 규칙이 의도대로 동작했나
            - (익절·재매수·한도 등 원칙에 적힌 동작별로 한 줄)
            ## 부족한 데이터
            - (없으면 "없음". 있으면 proposals 에도 올린다)

            반드시 아래 JSON 하나만 출력한다(설명·코드 펜스 금지):
            {"journal": "위 형식의 마크다운", "proposals": [{"title": "짧은 제목", "body": "마크다운. 무엇을 왜 어떻게 바꾸자는지 + 근거"}]}
            제안할 게 없으면 proposals 는 빈 배열이다.
            """.trimIndent()
    }
}

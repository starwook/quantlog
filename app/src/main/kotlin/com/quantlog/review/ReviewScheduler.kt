package com.quantlog.review

import mu.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

private val log = KotlinLogging.logger {}

/** 국내 정규장 마감(15:30) 직후인 [REVIEW_TIME](15:31) 에 평일마다 리뷰를 한 번 돌린다. `quantlog.review.enabled=true` 일 때만 뜬다. */
@Component
@ConditionalOnProperty(prefix = "quantlog.review", name = ["enabled"], havingValue = "true")
class ReviewScheduler(
    private val reviewService: ReviewService,
) {
    @Scheduled(cron = "0 31 15 * * MON-FRI", zone = "Asia/Seoul")
    fun review() {
        runCatching { reviewService.run() }
            .onSuccess { log.info { "[리뷰] 일지=${it.journalId} 제안=${it.proposalIds.size}건" } }
            .onFailure { log.error(it) { "[리뷰] 실패" } }
    }
}

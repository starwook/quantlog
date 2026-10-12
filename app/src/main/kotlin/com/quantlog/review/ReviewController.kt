package com.quantlog.review

import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import java.nio.file.Path

/** 리뷰를 지금 한 번 돌린다(Claude API 호출 = 비용 발생). 스케줄러와 무관하게 사용자가 직접 부를 때만 실행된다. */
@RestController
class ReviewController(
    private val reviewService: ReviewService,
) {
    @PostMapping("/review/run")
    fun run(): Map<String, Any> =
        reviewService.run().let { mapOf("journal" to it.journal.toString(), "proposals" to it.proposals.map(Path::toString)) }
}

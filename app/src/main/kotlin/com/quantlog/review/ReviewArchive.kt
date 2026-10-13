package com.quantlog.review

import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate

/** 날짜 목록의 한 줄: 그날 일지·제안이 몇 건이고 그중 즐겨찾기가 몇 건인지. */
data class DaySummary(val day: LocalDate, val journals: Int, val proposals: Int, val favorites: Int)

/** [hasMore] 가 true 면 [limit] 보다 더 있다는 뜻 — 화면의 "더 보기"가 이걸 본다. */
data class Page<T>(val items: List<T>, val hasMore: Boolean)

data class DayNeighbors(val previous: LocalDate?, val next: LocalDate?)

/**
 * 일일 리뷰의 일지·원칙 변경 제안 저장소(DB). 날이 많이 쌓여도 화면은 요청한 만큼만 읽는다 —
 * 날짜 목록은 날짜와 건수만 가져오고, 본문은 날짜 하나를 열 때 그날 것만 읽는다.
 * **원칙 파일(playbook/principles.md)은 여기서 다루지 않는다.** 사용자와 토론해서만 바꾸는 문서라 git 파일로 남는다.
 */
@Service
class ReviewArchive(
    private val journals: ReviewJournalRepository,
    private val proposals: ReviewProposalRepository,
    private val clock: Clock,
) {
    fun saveJournal(
        day: LocalDate,
        title: String,
        body: String,
    ): ReviewJournal = journals.save(ReviewJournal(day, title.trim(), body.trim(), clock.instant()))

    fun saveProposal(
        day: LocalDate,
        title: String,
        body: String,
    ): ReviewProposal = proposals.save(ReviewProposal(day, title.trim().take(TITLE_MAX), body.trim(), createdAt = clock.instant()))

    /** 일지나 제안이 있는 날짜를 최신부터 [limit]일. 일지 쪽·제안 쪽에서 각각 [limit]+1 개씩만 가져와 합친다. */
    @Transactional(readOnly = true)
    fun days(limit: Int): Page<DaySummary> {
        val window = PageRequest.of(0, limit + 1)
        val days = (journals.days(window) + proposals.days(window)).distinct().sortedDescending()
        val shown = days.take(limit)
        val journalCounts = journals.countByDays(shown).associate { it.day to it.count.toInt() }
        val proposalCounts = proposals.countByDays(shown).associateBy { it.day }
        val summaries =
            shown.map {
                DaySummary(
                    it,
                    journalCounts[it] ?: 0,
                    proposalCounts[it]?.count?.toInt() ?: 0,
                    proposalCounts[it]?.favorites?.toInt() ?: 0,
                )
            }
        return Page(summaries, hasMore = days.size > limit)
    }

    @Transactional(readOnly = true)
    fun journalsOf(day: LocalDate): List<ReviewJournal> = journals.findAllByDayOrderByCreatedAtAscIdAsc(day)

    @Transactional(readOnly = true)
    fun proposalsOf(day: LocalDate): List<ReviewProposal> = proposals.findAllByReviewDayOrderByCreatedAtAscIdAsc(day)

    /** [day] 바로 앞·뒤로 기록이 있는 날짜. 없으면 null. */
    @Transactional(readOnly = true)
    fun neighbors(day: LocalDate): DayNeighbors =
        DayNeighbors(
            previous = listOfNotNull(journals.dayBefore(day), proposals.dayBefore(day)).maxOrNull(),
            next = listOfNotNull(journals.dayAfter(day), proposals.dayAfter(day)).minOrNull(),
        )

    @Transactional(readOnly = true)
    fun favorites(limit: Int): Page<ReviewProposal> {
        val found = proposals.favorites(PageRequest.of(0, limit + 1))
        return Page(found.take(limit), hasMore = found.size > limit)
    }

    @Transactional(readOnly = true)
    fun favoriteCount(): Long = proposals.countByFavoriteTrue()

    /** 즐겨찾기를 [favorite] 으로 맞춘다(이미 그 상태여도 안전). 그런 제안이 없으면 false. */
    @Transactional
    fun setFavorite(
        proposalId: Long,
        favorite: Boolean,
    ): Boolean {
        val proposal = proposals.findById(proposalId).orElse(null) ?: return false
        proposal.favorite = favorite
        return true
    }

    private companion object {
        const val TITLE_MAX = 200
    }
}

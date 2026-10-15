package com.quantlog.review

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.time.LocalDate

/** 일일 리뷰가 남긴 일지 1건(`review_journal`). [day] 는 리뷰 대상일이고, 같은 날 여러 번 돌리면 행이 여러 개다. */
@Entity
@Table(
    name = "review_journal",
    indexes = [Index(name = "idx_review_journal_day", columnList = "day")],
)
class ReviewJournal(
    @Column(nullable = false)
    val day: LocalDate,
    @Column(nullable = false, length = 100)
    val title: String,
    @Column(nullable = false, columnDefinition = "TEXT")
    val body: String,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set
}

/** 원칙 변경 제안 1건(`review_proposal`). [reviewDay] 는 제안이 나온 리뷰의 대상일, [favorite] 은 즐겨찾기 여부. */
@Entity
@Table(
    name = "review_proposal",
    indexes = [
        Index(name = "idx_review_proposal_day", columnList = "review_day"),
        Index(name = "idx_review_proposal_favorite", columnList = "favorite, created_at"),
    ],
)
class ReviewProposal(
    @Column(name = "review_day", nullable = false)
    val reviewDay: LocalDate,
    @Column(nullable = false, length = 200)
    val title: String,
    @Column(nullable = false, columnDefinition = "TEXT")
    val body: String,
    @Column(nullable = false, length = 20)
    var status: String = STATUS_PENDING,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @Column(nullable = false)
    var favorite: Boolean = false

    companion object {
        const val STATUS_PENDING = "검토 대기"
    }
}

/** 날짜별 건수 집계 한 줄. [favorites] 는 제안 쪽에서만 쓴다. */
data class DayCount(val day: LocalDate, val count: Long, val favorites: Long) {
    // JPQL 의 `select new` 가 인자 개수로 생성자를 찾으므로 기본값 대신 두 인자짜리를 따로 둔다.
    constructor(day: LocalDate, count: Long) : this(day, count, 0)
}

interface ReviewJournalRepository : JpaRepository<ReviewJournal, Long> {
    /** 일지가 있는 날짜를 최신부터. 페이지 크기만큼만 가져온다. */
    @Query("select distinct j.day from ReviewJournal j order by j.day desc")
    fun days(pageable: Pageable): List<LocalDate>

    fun findAllByDayOrderByCreatedAtAscIdAsc(day: LocalDate): List<ReviewJournal>

    @Query("select new com.quantlog.review.DayCount(j.day, count(j)) from ReviewJournal j where j.day in :days group by j.day")
    fun countByDays(
        @Param("days") days: Collection<LocalDate>,
    ): List<DayCount>

    @Query("select max(j.day) from ReviewJournal j where j.day < :day")
    fun dayBefore(
        @Param("day") day: LocalDate,
    ): LocalDate?

    @Query("select min(j.day) from ReviewJournal j where j.day > :day")
    fun dayAfter(
        @Param("day") day: LocalDate,
    ): LocalDate?
}

interface ReviewProposalRepository : JpaRepository<ReviewProposal, Long> {
    @Query("select distinct p.reviewDay from ReviewProposal p order by p.reviewDay desc")
    fun days(pageable: Pageable): List<LocalDate>

    fun findAllByReviewDayOrderByCreatedAtAscIdAsc(reviewDay: LocalDate): List<ReviewProposal>

    @Query(
        "select new com.quantlog.review.DayCount(p.reviewDay, count(p), sum(case when p.favorite = true then 1 else 0 end)) " +
            "from ReviewProposal p where p.reviewDay in :days group by p.reviewDay",
    )
    fun countByDays(
        @Param("days") days: Collection<LocalDate>,
    ): List<DayCount>

    @Query("select max(p.reviewDay) from ReviewProposal p where p.reviewDay < :day")
    fun dayBefore(
        @Param("day") day: LocalDate,
    ): LocalDate?

    @Query("select min(p.reviewDay) from ReviewProposal p where p.reviewDay > :day")
    fun dayAfter(
        @Param("day") day: LocalDate,
    ): LocalDate?

    /** 즐겨찾기한 제안, 최신부터. */
    @Query("select p from ReviewProposal p where p.favorite = true order by p.createdAt desc, p.id desc")
    fun favorites(pageable: Pageable): List<ReviewProposal>

    fun countByFavoriteTrue(): Long
}

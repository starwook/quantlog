package com.quantlog.news

import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** 화면 한 번에 보여 줄 뉴스·공시와 소스별 상태. */
data class NewsFeed(
    val items: List<NewsItem>,
    val sources: List<NewsSourceResult>,
    val fetchedAt: Instant,
)

/**
 * 모든 소스를 모아 중복을 없애고 최신순으로 합친다. 소스 호출 한도를 아끼려고 [NewsProperties.cacheSeconds] 동안은 직전 결과를 돌려준다.
 * 저장하지 않는다 — 화면을 열 때 가져와 보여 줄 뿐이고, 판단(필터)에는 아직 쓰지 않는다(기획서 7.4).
 */
@Service
class NewsService(
    private val sources: List<NewsSource>,
    private val properties: NewsProperties,
    private val clock: Clock,
) {
    @Volatile
    private var cached: NewsFeed? = null

    @Synchronized
    fun feed(refresh: Boolean = false): NewsFeed {
        val now = clock.instant()
        cached?.let { if (!refresh && Duration.between(it.fetchedAt, now) < Duration.ofSeconds(properties.cacheSeconds)) return it }
        val results = sources.map { it.fetch() }
        val items =
            results.flatMap { it.items }
                .distinctBy { it.key }
                .sortedWith(compareByDescending<NewsItem> { it.publishedAt }.thenByDescending { it.key })
        return NewsFeed(items, results, now).also { cached = it }
    }
}

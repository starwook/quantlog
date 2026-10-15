package com.quantlog.news

import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals

class NewsServiceTest {
    private fun item(
        key: String,
        at: String,
    ) = NewsItem(NewsKind.NEWS, key, "https://x/$key", Instant.parse(at), key = key)

    private class FakeSource(var result: NewsSourceResult) : NewsSource {
        override val name = result.name
        var calls = 0

        override fun fetch() = result.also { calls++ }
    }

    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone() = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId?) = this

        override fun instant() = now
    }

    @Test
    fun `소스를 합쳐 중복을 없애고 최신순으로 정렬한다`() {
        val a = FakeSource(NewsSourceResult("a", listOf(item("1", "2026-10-15T01:00:00Z"), item("2", "2026-10-15T03:00:00Z"))))
        val b = FakeSource(NewsSourceResult("b", listOf(item("1", "2026-10-15T01:00:00Z"), item("3", "2026-10-15T02:00:00Z"))))
        val service = NewsService(listOf(a, b), NewsProperties(), MutableClock(Instant.parse("2026-10-15T04:00:00Z")))

        assertEquals(listOf("2", "3", "1"), service.feed().items.map { it.key })
    }

    @Test
    fun `캐시 시간 안에는 다시 부르지 않고 새로고침이나 시간이 지나면 부른다`() {
        val source = FakeSource(NewsSourceResult("a"))
        val clock = MutableClock(Instant.parse("2026-10-15T04:00:00Z"))
        val service = NewsService(listOf(source), NewsProperties(cacheSeconds = 60), clock)

        service.feed()
        clock.now = clock.now.plusSeconds(30)
        service.feed()
        assertEquals(1, source.calls)

        service.feed(refresh = true)
        assertEquals(2, source.calls)

        clock.now = clock.now.plusSeconds(61)
        service.feed()
        assertEquals(3, source.calls)
    }

    @Test
    fun `한 소스가 실패해도 다른 소스는 그대로 보여 준다`() {
        val ok = FakeSource(NewsSourceResult("ok", listOf(item("1", "2026-10-15T01:00:00Z"))))
        val bad = FakeSource(NewsSourceResult("bad", error = "HTTP 500"))
        val feed = NewsService(listOf(ok, bad), NewsProperties(), MutableClock(Instant.parse("2026-10-15T04:00:00Z"))).feed()

        assertEquals(1, feed.items.size)
        assertEquals("HTTP 500", feed.sources.last().error)
    }
}

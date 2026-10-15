package com.quantlog.news

import com.quantlog.common.KST
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import java.time.format.DateTimeFormatter

/** 분석 > 뉴스: 주식 관련 뉴스(속보 포함)와 상장사 공시를 한 목록으로 본다. 판단에는 아직 쓰지 않는다(기획서 7.4). */
@Controller
class NewsController(
    private val service: NewsService,
) {
    @GetMapping("/news")
    fun page(
        @RequestParam(required = false) kind: String?,
        @RequestParam(required = false, defaultValue = "false") refresh: Boolean,
        model: Model,
    ): String {
        val feed = service.feed(refresh)
        val selected = NewsKind.entries.firstOrNull { it.name.equals(kind, ignoreCase = true) }
        model.addAttribute("kind", selected?.name)
        model.addAttribute("items", feed.items.filter { selected == null || it.kind == selected }.take(MAX_ITEMS).map(::row))
        model.addAttribute("sources", feed.sources)
        model.addAttribute("fetchedAt", TIME.format(feed.fetchedAt.atZone(KST)))
        return "news"
    }

    private fun row(item: NewsItem): Map<String, Any?> {
        val at = item.publishedAt.atZone(KST)
        // DART 는 시각을 주지 않아 0시로 들어온다 — 날짜만 보여 준다.
        val time = if (item.kind == NewsKind.DISCLOSURE) DAY.format(at) else TIME.format(at)
        return mapOf(
            "kind" to item.kind.label,
            "disclosure" to (item.kind == NewsKind.DISCLOSURE),
            "title" to item.title,
            "link" to item.link,
            "summary" to item.summary,
            "symbol" to item.symbol,
            "time" to time,
        )
    }

    private companion object {
        const val MAX_ITEMS = 100
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")
        val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd")
    }
}

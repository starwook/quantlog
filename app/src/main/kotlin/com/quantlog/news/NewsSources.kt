package com.quantlog.news

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.quantlog.common.KST
import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private val log = KotlinLogging.logger {}

private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

private fun getJson(
    mapper: ObjectMapper,
    url: String,
    headers: Map<String, String> = emptyMap(),
): JsonNode {
    val builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET()
    headers.forEach { (k, v) -> builder.header(k, v) }
    val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
    check(response.statusCode() == 200) { "HTTP ${response.statusCode()}" }
    return mapper.readTree(response.body())
}

private fun encode(text: String) = URLEncoder.encode(text, StandardCharsets.UTF_8)

/** 네이버 검색 API 의 뉴스. 검색어별 최신순 30건. 제목·요약에 섞인 `<b>` 태그와 HTML 엔티티를 걷어 낸다. */
@Component
class NaverNewsSource(
    private val properties: NewsProperties,
    private val mapper: ObjectMapper,
) : NewsSource {
    override val name = "네이버 뉴스"

    override fun fetch(): NewsSourceResult {
        if (properties.naverClientId.isBlank() || properties.naverClientSecret.isBlank()) {
            return NewsSourceResult(name, disabled = true)
        }
        val headers =
            mapOf("X-Naver-Client-Id" to properties.naverClientId, "X-Naver-Client-Secret" to properties.naverClientSecret)
        val errors = mutableListOf<String>()
        val items =
            properties.queries.flatMap { query ->
                runCatching { parse(getJson(mapper, "$URL?query=${encode(query)}&display=30&sort=date", headers)) }
                    .onFailure {
                        log.warn { "[뉴스] 네이버 '$query' 조회 실패: ${it.message}" }
                        errors += "'$query' ${it.message}"
                    }.getOrDefault(emptyList())
            }
        return NewsSourceResult(name, items, errors.takeIf { it.isNotEmpty() }?.joinToString(" / "))
    }

    fun parse(root: JsonNode): List<NewsItem> =
        root.path("items").map { node ->
            val link = node.path("originallink").asText().ifBlank { node.path("link").asText() }
            NewsItem(
                kind = NewsKind.NEWS,
                title = clean(node.path("title").asText()),
                link = link,
                publishedAt = ZonedDateTime.parse(node.path("pubDate").asText(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant(),
                summary = clean(node.path("description").asText()),
                key = link,
            )
        }

    private companion object {
        const val URL = "https://openapi.naver.com/v1/search/news.json"
    }
}

internal fun clean(html: String): String =
    html.replace(Regex("<[^>]*>"), "")
        .replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

/** DART 전자공시. 오늘·어제 접수된 상장사(유가증권·코스닥) 공시. DART 는 접수 날짜만 주고 시각은 주지 않는다. */
@Component
class DartDisclosureSource(
    private val properties: NewsProperties,
    private val mapper: ObjectMapper,
    private val clock: Clock,
) : NewsSource {
    override val name = "DART 공시"

    override fun fetch(): NewsSourceResult {
        if (properties.dartApiKey.isBlank()) return NewsSourceResult(name, disabled = true)
        val today = LocalDate.now(clock.withZone(KST))
        val url =
            "$URL?crtfc_key=${encode(properties.dartApiKey)}&bgn_de=${today.minusDays(1).format(DATE)}" +
                "&end_de=${today.format(DATE)}&page_count=100"
        return runCatching { parse(getJson(mapper, url)) }
            .map { NewsSourceResult(name, it) }
            .getOrElse {
                log.warn { "[뉴스] DART 조회 실패: ${it.message}" }
                NewsSourceResult(name, error = it.message)
            }
    }

    /** status "000" 이 정상, "013" 은 조회 결과 없음(오류가 아님). 그 밖은 message 를 오류로 올린다. */
    fun parse(root: JsonNode): List<NewsItem> {
        when (val status = root.path("status").asText()) {
            "000" -> Unit
            "013" -> return emptyList()
            else -> error("DART $status ${root.path("message").asText()}")
        }
        return root.path("list")
            .filter { it.path("corp_cls").asText() in LISTED }
            .map { node ->
                val receiptNo = node.path("rcept_no").asText()
                NewsItem(
                    kind = NewsKind.DISCLOSURE,
                    title = node.path("report_nm").asText().trim(),
                    link = "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=$receiptNo",
                    publishedAt = LocalDate.parse(node.path("rcept_dt").asText(), DATE).atStartOfDay(KST).toInstant(),
                    summary = node.path("corp_name").asText(),
                    symbol = node.path("stock_code").asText().ifBlank { null },
                    key = receiptNo,
                )
            }
    }

    private companion object {
        const val URL = "https://opendart.fss.or.kr/api/list.json"
        val DATE: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE

        /** Y 유가증권, K 코스닥. N 코넥스·E 기타는 뺀다. */
        val LISTED = setOf("Y", "K")
    }
}

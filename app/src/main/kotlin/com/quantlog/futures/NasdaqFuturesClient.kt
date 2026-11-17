package com.quantlog.futures

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.quantlog.common.KST
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter

/** 나스닥100 선물 봉(과거 → 최근)과 전일 종가. [previousClose] 는 1분봉 조회에만 실려 온다. */
data class NasdaqFuturesQuote(
    val candles: List<Candle>,
    val previousClose: BigDecimal?,
)

/**
 * 야후 파이낸스 차트 응답(`chart.result[0]`)을 봉으로 푼다. 형식·실측: docs/kis-api/overseas-futures.md.
 * 시각은 한국 시간으로 바꾼다(화면의 다른 차트와 같은 기준). 값이 빈(null) 봉은 건너뛴다.
 */
object YahooChartParser {
    private val DATE: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE
    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HHmmss")

    fun parse(
        body: JsonNode,
        daily: Boolean,
    ): NasdaqFuturesQuote {
        val chart = body.path("chart")
        val result = chart.path("result").path(0)
        check(!result.isMissingNode) { chart.path("error").path("description").asText("야후 응답에 봉이 없습니다") }
        val quote = result.path("indicators").path("quote").path(0)
        val candles =
            result.path("timestamp").mapIndexedNotNull { i, ts ->
                val at = Instant.ofEpochSecond(ts.asLong()).atZone(KST).toLocalDateTime()
                Candle(
                    date = at.format(DATE),
                    time = if (daily) null else at.format(TIME),
                    open = quote.decimal("open", i) ?: return@mapIndexedNotNull null,
                    high = quote.decimal("high", i) ?: return@mapIndexedNotNull null,
                    low = quote.decimal("low", i) ?: return@mapIndexedNotNull null,
                    close = quote.decimal("close", i) ?: return@mapIndexedNotNull null,
                    volume = quote.path("volume").path(i).asLong(0),
                )
            }
        return NasdaqFuturesQuote(candles, result.path("meta").path("previousClose").takeIf { it.isNumber }?.decimalValue())
    }

    private fun JsonNode.decimal(
        field: String,
        index: Int,
    ): BigDecimal? = path(field).path(index).takeIf { it.isNumber }?.decimalValue()
}

/**
 * 나스닥100 선물(`NQ=F`) 시세를 야후 파이낸스에서 읽는다. 한투는 CME 유료 시세를 신청해야 줘서 쓰지 않는다
 * (docs/kis-api/overseas-futures.md). 야후는 약 10분 지연이고 공개 문서가 없는 통로라 언제든 막힐 수 있다 — 실패는 예외로 알린다.
 * 한도가 공개돼 있지 않아 [MINUTE_TTL]·[DAILY_TTL] 동안은 받은 값을 다시 쓴다(화면을 여러 개 열어도 호출이 늘지 않는다).
 */
@Component
class NasdaqFuturesClient(
    private val mapper: ObjectMapper,
    private val clock: Clock,
) {
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private val cache = HashMap<Boolean, Pair<Instant, NasdaqFuturesQuote>>()

    /** [daily] 면 일봉(6개월), 아니면 1분봉(최근 이틀 — 한국 시간 밤·새벽 거래 포함). */
    @Synchronized
    fun quote(daily: Boolean): NasdaqFuturesQuote {
        val now = clock.instant()
        cache[daily]?.let { (at, quote) -> if (Duration.between(at, now) < (if (daily) DAILY_TTL else MINUTE_TTL)) return quote }
        return fetch(daily).also { cache[daily] = now to it }
    }

    private fun fetch(daily: Boolean): NasdaqFuturesQuote {
        val query = if (daily) "interval=1d&range=6mo" else "interval=1m&range=2d"
        val request =
            HttpRequest.newBuilder(URI.create("$URL?$query"))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", "Mozilla/5.0")
                .GET()
                .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        check(response.statusCode() == 200) { "야후 HTTP ${response.statusCode()}" }
        return YahooChartParser.parse(mapper.readTree(response.body()), daily)
    }

    private companion object {
        const val URL = "https://query1.finance.yahoo.com/v8/finance/chart/NQ=F"
        val MINUTE_TTL: Duration = Duration.ofSeconds(60)
        val DAILY_TTL: Duration = Duration.ofHours(1)
    }
}

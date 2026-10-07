package com.quantlog.stockmaster

import mu.KotlinLogging
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.zip.ZipInputStream

private val log = KotlinLogging.logger {}

/** 마스터 파일(zip)을 받아 압축을 풀고 `.mst` 원본 바이트를 돌려준다. 테스트에서는 가짜로 바꾼다. */
fun interface StockMasterSource {
    fun download(exchange: MasterExchange): ByteArray
}

/** KIS 공개 다운로드 주소에서 받는다. 인증(앱키·토큰)이 필요 없다. */
@Component
class HttpStockMasterSource : StockMasterSource {
    private val client = HttpClient.newHttpClient()

    override fun download(exchange: MasterExchange): ByteArray {
        val request = HttpRequest.newBuilder(URI.create(BASE_URL + exchange.fileName)).GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        check(response.statusCode() == 200) { "$exchange 마스터 다운로드 실패: HTTP ${response.statusCode()}" }
        ZipInputStream(response.body()).use { zip ->
            checkNotNull(zip.nextEntry) { "$exchange 마스터 zip 이 비어 있습니다" }
            return ByteArrayOutputStream().also { zip.copyTo(it) }.toByteArray()
        }
    }

    private companion object {
        const val BASE_URL = "https://new.real.download.dws.co.kr/common/master/"
    }
}

/** 종목 추가 화면 자동완성에 내려주는 값. */
data class StockSearchResult(
    val code: String,
    val name: String,
    val exchange: MasterExchange,
    val etf: Boolean,
    val tradingSuspended: Boolean,
)

@Service
class StockMasterService(
    private val repository: StockMasterRepository,
    private val source: StockMasterSource,
) {
    /** 코드가 [query] 로 시작하거나 이름에 [query] 가 들어간 종목. 코드 일치가 먼저 나온다. */
    fun search(query: String): List<StockSearchResult> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val page = PageRequest.of(0, SEARCH_LIMIT)
        return (repository.searchByCodePrefix(q.uppercase(), page) + repository.searchByNameContaining(q, page))
            .distinctBy { it.shortCode }
            .take(SEARCH_LIMIT)
            .map { StockSearchResult(it.shortCode, it.name, it.exchange, it.isEtf, it.tradingSuspended) }
    }

    /**
     * 마스터 파일을 받아 DB 를 맞춘다(없으면 추가, 있으면 갱신, 파일에서 사라진 종목은 삭제).
     * 시장별로 독립이라 한쪽이 실패(다운로드·형식 불일치)해도 그 시장은 기존 데이터를 그대로 두고 다른 시장은 진행한다.
     */
    fun sync() {
        MasterExchange.entries.forEach { exchange ->
            runCatching { syncExchange(exchange) }
                .onFailure { log.error(it) { "[종목 마스터] $exchange 동기화 실패 — 기존 데이터 유지" } }
        }
    }

    private fun syncExchange(exchange: MasterExchange) {
        val records = StockMasterParser.parse(source.download(exchange), exchange)
        // 비정상적으로 적게 오면(빈 파일 등) 전부 지워 버리지 않도록 거부한다.
        check(records.size >= MIN_PLAUSIBLE_COUNT) { "$exchange 마스터가 ${records.size}건뿐입니다 — 반영하지 않습니다" }
        val existing = repository.findAllByExchange(exchange).associateBy { it.shortCode }
        val toSave =
            records.map { record -> existing[record.shortCode]?.apply { update(record) } ?: StockMaster.from(record) }
        repository.saveAll(toSave)
        val stale = existing.keys - records.map { it.shortCode }.toSet()
        if (stale.isNotEmpty()) repository.deleteAllByIdInBatch(stale)
        log.info { "[종목 마스터] $exchange ${records.size}건 반영, ${stale.size}건 삭제" }
    }

    fun isEmpty(): Boolean = repository.count() == 0L

    private companion object {
        const val SEARCH_LIMIT = 20
        const val MIN_PLAUSIBLE_COUNT = 100
    }
}

/** 처음 켰을 때 비어 있으면 바로 받고, 이후 매일 장 시작 전에 갱신한다(KIS 가 08:45 에도 갱신해서 그 뒤에 받는다). */
@Component
class StockMasterScheduler(
    private val service: StockMasterService,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        if (service.isEmpty()) Thread({ service.sync() }, "stock-master-initial-sync").start()
    }

    @Scheduled(cron = "0 50 8 * * *", zone = "Asia/Seoul")
    fun daily() = service.sync()
}

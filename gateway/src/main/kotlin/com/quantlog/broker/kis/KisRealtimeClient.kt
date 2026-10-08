package com.quantlog.broker.kis

import com.fasterxml.jackson.databind.ObjectMapper
import com.quantlog.broker.CandleUpdated
import com.quantlog.broker.Market
import com.quantlog.broker.MinuteCandle
import com.quantlog.broker.PriceTick
import com.quantlog.broker.RealtimePriceFeed
import com.quantlog.broker.RealtimeSymbolSource
import com.quantlog.marketdata.MinuteCandleStore
import jakarta.annotation.PreDestroy
import mu.KotlinLogging
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private val log = KotlinLogging.logger {}
private val KST = ZoneId.of("Asia/Seoul")

data class LivePrice(val price: BigDecimal, val at: Instant)

/**
 * KIS 실시간 체결가(H0STCNT0) WebSocket 구독. 틱마다 세 가지를 한다:
 * (1) 최신가 캐시 갱신 — KisMockBroker.quote() 가 REST 대신 이걸 먼저 본다(RiskGuard.checkBuy/보유
 *     조회는 여전히 REST — 실시간은 가격만).
 * (2) 1분 단위로 묶어서 분봉 저장 — REST 폴링(EntryScheduler)과 saveIfNew 로 dedup 되어 공존 가능.
 * (3) 진행 중인 분봉 갱신을 [CandleUpdated] 이벤트로 발행 — 화면 중계(앱)가 듣는다.
 * (4) [PriceTick] 이벤트 발행 — 청산 감시 등이 틱에 바로 반응한다(수신 스레드에서는 가볍게 넘기기만 해야 한다).
 * KIS 키가 없으면 켜지 않고(연결 안 됨 → isLive=false) REST 폴링만으로 동작한다.
 */
@Component
class KisRealtimeClient(
    private val properties: KisProperties,
    private val tokenProvider: KisTokenProvider,
    private val candleStore: MinuteCandleStore,
    private val objectMapper: ObjectMapper,
    private val eventPublisher: ApplicationEventPublisher,
    private val symbolSource: RealtimeSymbolSource,
    private val fillNotices: KisFillNoticeHandler,
) : RealtimePriceFeed {
    private val httpClient = HttpClient.newHttpClient()
    private val liveQuotes = ConcurrentHashMap<String, LivePrice>()
    private val inProgress = ConcurrentHashMap<String, MutableCandle>()
    private var webSocket: WebSocket? = null

    @Volatile
    private var connected = false

    /** 지금 KIS 에 구독 걸려 있는 종목. 연결이 끊기면 비운다(재연결 뒤 [refreshSubscriptions] 가 다시 건다). */
    private val subscribed = ConcurrentHashMap.newKeySet<String>()

    /** java.net.http.WebSocket 은 이전 sendText 가 끝나기 전에 또 보내면 예외라서, 전송을 한 줄로 이어 보낸다. */
    private var sendChain: CompletableFuture<*> = CompletableFuture.completedFuture(null)

    fun latestPrice(symbol: String): LivePrice? = liveQuotes[symbol]

    override fun isLive(
        market: Market,
        symbol: String,
    ): Boolean = connected && market == Market.KR && symbol in subscribed

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        if (!properties.hasCredentials) {
            log.warn { "[실시간 시세] KIS 키가 없어 실시간을 켜지 않는다 — REST 폴링만 동작" }
            return
        }
        connect()
    }

    @PreDestroy
    fun stop() {
        connected = false
        webSocket?.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown")
    }

    private fun connect() {
        httpClient.newWebSocketBuilder()
            .buildAsync(URI.create(properties.wsUrl), Listener())
            .thenAccept { ws ->
                webSocket = ws
                subscribeFillNotices(ws)
                refreshSubscriptions()
            }
            .exceptionally { e ->
                log.warn(e) { "[실시간 시세] 연결 실패 — ${RECONNECT_DELAY.seconds}초 후 재시도" }
                scheduleReconnect()
                null
            }
    }

    private fun scheduleReconnect() {
        CompletableFuture
            .delayedExecutor(RECONNECT_DELAY.toMillis(), TimeUnit.MILLISECONDS)
            .execute { connect() }
    }

    /** DB 기준 구독 목록과 실제 구독을 맞춘다. 종목이 추가·삭제되면 이 주기 안에 따라온다. */
    @Scheduled(fixedDelay = REFRESH_INTERVAL_MILLIS, initialDelay = REFRESH_INTERVAL_MILLIS)
    @Synchronized
    fun refreshSubscriptions() {
        val ws = webSocket?.takeIf { connected } ?: return
        val wanted = symbolSource.symbols(Market.KR).take(properties.realtimeMaxSubscriptions).toSet()
        (wanted - subscribed).forEach { send(ws, SUBSCRIBE, TR_ID, it) }
        (subscribed - wanted).forEach { send(ws, UNSUBSCRIBE, TR_ID, it) }
        subscribed.retainAll(wanted)
        subscribed.addAll(wanted)
    }

    /** 연결마다 한 번: 계좌 체결통보를 구독한다. tr_key 는 HTS ID — 비어 있으면 건너뛴다(시세만 동작). */
    private fun subscribeFillNotices(ws: WebSocket) {
        if (properties.htsId.isBlank()) {
            log.warn { "[체결통보] kis.mock.hts-id 가 비어 있어 구독하지 않는다" }
            return
        }
        send(ws, SUBSCRIBE, KisFillNoticeHandler.DOMESTIC_TR_ID, properties.htsId)
    }

    private fun send(
        ws: WebSocket,
        trType: String,
        trId: String,
        trKey: String,
    ) {
        val message =
            objectMapper.writeValueAsString(
                mapOf(
                    "header" to
                        mapOf(
                            "approval_key" to tokenProvider.approvalKey(),
                            "custtype" to "P",
                            "tr_type" to trType,
                            "content-type" to "utf-8",
                        ),
                    "body" to mapOf("input" to mapOf("tr_id" to trId, "tr_key" to trKey)),
                ),
            )
        sendChain =
            sendChain
                .handle { _, _ -> null }
                .thenCompose { ws.sendText(message, true) }
    }

    private fun handle(raw: String) {
        if (raw.startsWith("0") || raw.startsWith("1")) {
            val parts = raw.split("|", limit = 4)
            if (parts.size == 4 && parts[1] in KisFillNoticeHandler.TR_IDS) {
                fillNotices.onData(parts[1], encrypted = raw.startsWith("1"), recordCount = parts[2].toIntOrNull() ?: 1, payload = parts[3])
            } else {
                handleTick(raw)
            }
            return
        }
        val node = objectMapper.readTree(raw)
        val trId = node.path("header").path("tr_id").asText()
        when {
            trId == "PINGPONG" -> webSocket?.sendPong(ByteBuffer.wrap(raw.toByteArray()))
            trId in KisFillNoticeHandler.TR_IDS -> fillNotices.onSubscribeResponse(trId, node)
        }
    }

    /** 필드 순서는 H0STCNT0 실시간체결가 스펙(examples_llm/domestic_stock/ccnl_krx) 기준. */
    private fun handleTick(raw: String) {
        val parts = raw.split("|")
        if (parts.size < 4) return
        val fields = parts[3].split("^")
        if (fields.size <= CNTG_VOL_INDEX) return

        val symbol = fields[SYMBOL_INDEX]
        val time = runCatching { LocalTime.parse(fields[TIME_INDEX], HOUR_FORMAT) }.getOrNull() ?: return
        val price = fields[PRICE_INDEX].toBigDecimalOrNull() ?: return
        val volume = fields[CNTG_VOL_INDEX].toLongOrNull() ?: 0L

        liveQuotes[symbol] = LivePrice(price, Instant.now())
        accumulate(symbol, time, price, volume)
        eventPublisher.publishEvent(PriceTick(Market.KR, symbol, price))
    }

    private fun accumulate(
        symbol: String,
        time: LocalTime,
        price: BigDecimal,
        volume: Long,
    ) {
        var justFinished: MinuteCandle? = null
        val minute = time.withSecond(0).withNano(0)
        val current =
            inProgress.compute(symbol) { _, existing ->
                if (existing == null || existing.minute != minute) {
                    if (existing != null) justFinished = existing.toCandle()
                    MutableCandle(minute, price)
                } else {
                    existing.apply {
                        high = high.max(price)
                        low = low.min(price)
                        close = price
                        this.volume += volume
                    }
                }
            }!!
        justFinished?.let { candleStore.saveIfNew(Market.KR, symbol, it) }
        eventPublisher.publishEvent(CandleUpdated(Market.KR, symbol, current.toCandle()))
    }

    private fun MutableCandle.toCandle() = MinuteCandle(LocalDate.now(KST), minute, open, high, low, close, volume)

    private inner class Listener : WebSocket.Listener {
        private val buffer = StringBuilder()

        override fun onOpen(webSocket: WebSocket) {
            log.info { "[실시간 시세] 연결됨" }
            connected = true
            webSocket.request(1)
        }

        override fun onText(
            webSocket: WebSocket,
            data: CharSequence,
            last: Boolean,
        ): CompletionStage<*>? {
            buffer.append(data)
            if (last) {
                runCatching { handle(buffer.toString()) }.onFailure { log.warn(it) { "[실시간 시세] 메시지 처리 실패" } }
                buffer.setLength(0)
            }
            webSocket.request(1)
            return null
        }

        override fun onClose(
            webSocket: WebSocket,
            statusCode: Int,
            reason: String,
        ): CompletionStage<*>? {
            connected = false
            subscribed.clear()
            log.warn { "[실시간 시세] 연결 종료(code=$statusCode, $reason) — ${RECONNECT_DELAY.seconds}초 후 재연결" }
            scheduleReconnect()
            return null
        }

        override fun onError(
            webSocket: WebSocket,
            error: Throwable,
        ) {
            connected = false
            log.warn(error) { "[실시간 시세] 에러" }
        }
    }

    private class MutableCandle(val minute: LocalTime, price: BigDecimal) {
        val open: BigDecimal = price
        var high: BigDecimal = price
        var low: BigDecimal = price
        var close: BigDecimal = price
        var volume: Long = 0
    }

    private companion object {
        const val TR_ID = "H0STCNT0"
        const val SUBSCRIBE = "1"
        const val UNSUBSCRIBE = "2"
        const val REFRESH_INTERVAL_MILLIS = 10_000L
        const val SYMBOL_INDEX = 0
        const val TIME_INDEX = 1
        const val PRICE_INDEX = 2
        const val CNTG_VOL_INDEX = 12
        val RECONNECT_DELAY: Duration = Duration.ofSeconds(5)
        val HOUR_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HHmmss")
    }
}

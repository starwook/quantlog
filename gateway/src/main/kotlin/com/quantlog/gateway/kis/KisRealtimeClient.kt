package com.quantlog.gateway.kis

import com.fasterxml.jackson.databind.ObjectMapper
import com.quantlog.gateway.broker.Market
import com.quantlog.gateway.broker.RealtimePriceFeed
import com.quantlog.gateway.broker.RealtimeSymbolSource
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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private val log = KotlinLogging.logger {}

data class LivePrice(val price: BigDecimal, val at: Instant)

/** 한투 실시간 시세 메시지 원문 한 건(`0|H0STCNT0|001|...`). StreamHub 가 앱으로 그대로 전달한다. */
data class RawRealtimeMessage(val raw: String)

/**
 * KIS 실시간 WebSocket 구독. 시세(H0STCNT0)는 받은 한투 메시지를 **해석하지 않고 원문 그대로** [RawRealtimeMessage] 이벤트로 내보낸다
 * (StreamHub 가 앱에 전달 — 틱·분봉 해석은 앱이 한투 문서대로 한다). 예외는 하나: KisMockBroker.quote() 가 REST 대신 쓰는 최신가 캐시를
 * 위해 종목·가격 칸만 읽는다(REST 조회를 한투 통로로 바꾸는 작업에서 앱으로 옮길 임시 해석).
 * KIS 키가 없으면 켜지 않고(연결 안 됨 → isLive=false) REST 폴링만으로 동작한다.
 */
@Component
class KisRealtimeClient(
    private val properties: KisProperties,
    private val tokenProvider: KisTokenProvider,
    private val objectMapper: ObjectMapper,
    private val eventPublisher: ApplicationEventPublisher,
    private val symbolSource: RealtimeSymbolSource,
    private val fillNotices: KisFillNoticeHandler,
) : RealtimePriceFeed {
    private val httpClient = HttpClient.newHttpClient()
    private val liveQuotes = ConcurrentHashMap<String, LivePrice>()
    private var webSocket: WebSocket? = null

    @Volatile
    private var connected = false

    /** 지금 연결이 붙은 시각. 연결이 끊겼다 다시 붙으면 바뀐다 — 앱이 이걸 보고 놓친 체결을 REST 로 보충한다. */
    @Volatile
    private var connectedAt: Instant? = null

    /** 재연결을 한 번만 예약하려는 표시(onError 와 onClose 가 둘 다 불릴 수 있다). */
    private val reconnectScheduled = AtomicBoolean(false)

    /** 지금 KIS 에 구독 걸려 있는 종목. 연결이 끊기면 비운다(재연결 뒤 [refreshSubscriptions] 가 다시 건다). */
    private val subscribed = ConcurrentHashMap.newKeySet<String>()

    /** java.net.http.WebSocket 은 이전 sendText 가 끝나기 전에 또 보내면 예외라서, 전송을 한 줄로 이어 보낸다. */
    private var sendChain: CompletableFuture<*> = CompletableFuture.completedFuture(null)

    fun latestPrice(symbol: String): LivePrice? = liveQuotes[symbol]

    /** 연결이 붙어 있으면 붙은 시각, 아니면 null. */
    fun wsConnectedAt(): Instant? = connectedAt.takeIf { connected }

    /** 지금 실시간으로 구독 중인 종목(연결이 끊겼으면 빈 목록). */
    fun liveSymbols(): List<String> = if (connected) subscribed.toList().sorted() else emptyList()

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
        reconnectScheduled.set(false)
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
        if (!reconnectScheduled.compareAndSet(false, true)) return
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

    /** 최신가 캐시용으로 종목·가격 칸만 읽고, 메시지는 원문 그대로 내보낸다. 칸 번호가 어긋나도 원문 전달은 영향이 없다. */
    private fun handleTick(raw: String) {
        val parts = raw.split("|", limit = 4)
        if (parts.size < 4) return
        val fields = parts[3].split("^")
        if (fields.size > PRICE_INDEX) {
            val price = fields[PRICE_INDEX].toBigDecimalOrNull()
            if (price != null) liveQuotes[fields[SYMBOL_INDEX]] = LivePrice(price, Instant.now())
        }
        eventPublisher.publishEvent(RawRealtimeMessage(raw))
    }

    private inner class Listener : WebSocket.Listener {
        private val buffer = StringBuilder()

        override fun onOpen(webSocket: WebSocket) {
            log.info { "[실시간 시세] 연결됨" }
            connected = true
            connectedAt = Instant.now()
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
            // onError 뒤에는 연결이 끝난 것이라 onClose 가 안 올 수 있다 — 여기서도 다시 붙인다(예전엔 이 경우 영영 끊겨 있었다).
            connected = false
            subscribed.clear()
            log.warn(error) { "[실시간 시세] 에러 — ${RECONNECT_DELAY.seconds}초 후 재연결" }
            runCatching { webSocket.abort() }
            scheduleReconnect()
        }
    }

    private companion object {
        const val TR_ID = "H0STCNT0"
        const val SUBSCRIBE = "1"
        const val UNSUBSCRIBE = "2"
        const val REFRESH_INTERVAL_MILLIS = 10_000L
        const val SYMBOL_INDEX = 0
        const val PRICE_INDEX = 2
        val RECONNECT_DELAY: Duration = Duration.ofSeconds(5)
    }
}

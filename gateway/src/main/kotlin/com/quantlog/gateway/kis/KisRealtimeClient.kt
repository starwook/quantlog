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

/** 한투 실시간 시세 메시지 원문 한 건(`0|H0STCNT0|001|...`). StreamHub 가 앱으로 그대로 전달한다. */
data class RawRealtimeMessage(val raw: String)

/**
 * 한투 실시간 연결로 체결통보를 받을 수 있는 상태가 바뀌었다. StreamHub 가 앱에 `kis_ws` 로 그대로 전한다(해석·정책은 앱).
 * [fillNoticeLive] 는 연결이 붙고 체결통보 구독 성공 응답까지 받았는가 — 연결만 붙고 구독이 안 됐으면 false 다.
 * [downSince] 는 다시 붙었을 때(true) 처음 끊긴 시각, 끊겼을 때(false)도 그 끊김이 시작된 시각이다.
 */
data class KisConnectionChanged(val fillNoticeLive: Boolean, val at: Instant, val downSince: Instant?)

/**
 * KIS 실시간 WebSocket 구독. 시세(H0STCNT0)는 받은 한투 메시지를 **해석하지 않고 원문 그대로** [RawRealtimeMessage] 이벤트로 내보낸다
 * (StreamHub 가 앱에 전달 — 틱·분봉·최신가 해석은 앱이 한투 문서대로 한다).
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
    private var webSocket: WebSocket? = null

    @Volatile
    private var connected = false

    /** 지금 연결이 붙은 시각. 연결이 끊겼다 다시 붙으면 바뀐다 — 앱이 이걸 보고 놓친 체결을 REST 로 보충한다. */
    @Volatile
    private var connectedAt: Instant? = null

    /** 재연결을 한 번만 예약하려는 표시(onError 와 onClose 가 둘 다 불릴 수 있다). */
    private val reconnectScheduled = AtomicBoolean(false)

    /** 체결통보 구독까지 성공해 통보를 받을 수 있는 상태인가. */
    @Volatile
    private var fillNoticeLive = false

    /** 체결통보를 못 받기 시작한 시각(끊긴 시각). 다시 구독되면 비운다. */
    @Volatile
    private var downSince: Instant? = null

    /** 마지막으로 기다리지 않고 바로 다시 붙인 시각. 바로 붙이기가 연달아 실패하면 [RECONNECT_DELAY] 간격으로 늦춘다. */
    @Volatile
    private var lastImmediateReconnectAt: Instant = Instant.EPOCH

    /** 지금 KIS 에 구독 걸려 있는 종목. 연결이 끊기면 비운다(재연결 뒤 [refreshSubscriptions] 가 다시 건다). */
    private val subscribed = ConcurrentHashMap.newKeySet<String>()

    /** 지금 KIS 에 구독 걸려 있는 야간선물 종목코드. [subscribed] 와 같은 방식으로 맞춘다. */
    private val subscribedFutures = ConcurrentHashMap.newKeySet<String>()

    /** java.net.http.WebSocket 은 이전 sendText 가 끝나기 전에 또 보내면 예외라서, 전송을 한 줄로 이어 보낸다. */
    private var sendChain: CompletableFuture<*> = CompletableFuture.completedFuture(null)

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

    /**
     * 다시 연결을 예약한다. [immediate] 면 기다리지 않고 바로 붙는다 — 한투가 매시 정각에 끊는데(2026-10-14 실측) 5초를 기다리면 그 사이 체결통보가
     * 유실되기 때문이다(버그 수정). 다만 바로 붙이기를 [RECONNECT_DELAY] 안에 또 하게 되면(붙자마자 또 끊김·연결 실패) 한투를 두드리지 않게 [RECONNECT_DELAY] 를 기다린다.
     */
    private fun scheduleReconnect(
        immediate: Boolean = false,
        fixedDelay: Duration? = null,
    ): Duration? {
        if (!reconnectScheduled.compareAndSet(false, true)) return null
        val now = Instant.now()
        val delay =
            when {
                fixedDelay != null -> fixedDelay
                immediate && Duration.between(lastImmediateReconnectAt, now) >= RECONNECT_DELAY -> {
                    lastImmediateReconnectAt = now
                    Duration.ZERO
                }
                else -> RECONNECT_DELAY
            }
        CompletableFuture
            .delayedExecutor(delay.toMillis(), TimeUnit.MILLISECONDS)
            .execute { connect() }
        return delay
    }

    /** 체결통보를 못 받게 됐다(연결 끊김·에러·접속키 무효). 끊김이 이어지는 동안 [downSince] 는 처음 끊긴 시각을 지킨다. */
    private fun markFillNoticesDown() {
        val now = Instant.now()
        val since = downSince ?: now.also { downSince = it }
        val wasLive = fillNoticeLive
        fillNoticeLive = false
        if (wasLive) eventPublisher.publishEvent(KisConnectionChanged(false, now, since))
    }

    /** 체결통보 구독 성공 응답을 받았다 — 이제 통보를 받는다. 끊겼다 다시 붙은 것이면 끊겨 있던 시간을 기록으로 남긴다. */
    private fun markFillNoticesLive() {
        if (fillNoticeLive) return
        val now = Instant.now()
        val since = downSince
        downSince = null
        fillNoticeLive = true
        if (since != null) {
            log.warn { "[실시간 시세] 체결통보 재구독 완료 — 끊긴 지 ${Duration.between(since, now).toMillis()}ms (그 사이 체결통보는 다시 오지 않는다)" }
        } else {
            log.info { "[실시간 시세] 체결통보 구독 완료" }
        }
        eventPublisher.publishEvent(KisConnectionChanged(true, now, since))
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

        val wantedFutures = symbolSource.nightFutures().toSet()
        (wantedFutures - subscribedFutures).forEach { send(ws, SUBSCRIBE, NIGHT_FUTURES_TR_ID, it) }
        (subscribedFutures - wantedFutures).forEach { send(ws, UNSUBSCRIBE, NIGHT_FUTURES_TR_ID, it) }
        subscribedFutures.retainAll(wantedFutures)
        subscribedFutures.addAll(wantedFutures)
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
            trId in KisFillNoticeHandler.TR_IDS ->
                if (fillNotices.onSubscribeResponse(trId, node)) {
                    markFillNoticesLive()
                } else {
                    log.warn { "[체결통보] $trId 구독 실패 — 연결은 붙어 있어도 체결통보를 받지 못한다: ${node.path("body").path("msg1").asText()}" }
                }
            trId == NIGHT_FUTURES_TR_ID -> log.info { "[야간선물] 구독 응답: ${node.path("body").path("msg1").asText()}" }
        }
        if (trId != "PINGPONG") {
            val message = node.path("body").path("msg1").asText()
            if (node.path("body").path("rt_cd").asText() == "1" && message.contains("invalid approval", ignoreCase = true)) {
                reconnectWithNewApprovalKey(trId)
            }
        }
    }

    /**
     * 한투가 접속키를 무효로 거절하면(다른 곳에서 같은 앱키로 접속키를 새로 받으면 이쪽이 무효가 된다) 구독이 영영 안 걸려
     * 체결통보·시세가 끊긴다. 키를 버리고 새로 받아 다시 연결·구독한다. 계속 거절되면 [APPROVAL_RETRY_DELAY] 마다 반복한다.
     */
    private fun reconnectWithNewApprovalKey(trId: String) {
        log.warn { "[실시간 시세] $trId 구독이 접속키 무효로 거절됨 — 접속키를 새로 받아 ${APPROVAL_RETRY_DELAY.seconds}초 후 다시 연결" }
        tokenProvider.invalidateApprovalKey()
        connected = false
        subscribed.clear()
        markFillNoticesDown()
        runCatching { webSocket?.abort() }
        scheduleReconnect(fixedDelay = APPROVAL_RETRY_DELAY)
    }

    /** 시세 메시지는 해석 없이 원문 그대로 내보낸다. 칸 번호가 어긋나도 게이트웨이는 영향이 없다. */
    private fun handleTick(raw: String) {
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
            subscribedFutures.clear()
            markFillNoticesDown()
            val delay = scheduleReconnect(immediate = true)
            log.warn { "[실시간 시세] 연결 종료(code=$statusCode, $reason) — ${delay?.let { "${it.toMillis()}ms 후 재연결" } ?: "재연결 예약됨"}" }
            return null
        }

        override fun onError(
            webSocket: WebSocket,
            error: Throwable,
        ) {
            // onError 뒤에는 연결이 끝난 것이라 onClose 가 안 올 수 있다 — 여기서도 다시 붙인다(예전엔 이 경우 영영 끊겨 있었다).
            connected = false
            subscribed.clear()
            subscribedFutures.clear()
            markFillNoticesDown()
            runCatching { webSocket.abort() }
            val delay = scheduleReconnect(immediate = true)
            log.warn(error) { "[실시간 시세] 에러 — ${delay?.let { "${it.toMillis()}ms 후 재연결" } ?: "재연결 예약됨"}" }
        }
    }

    private companion object {
        const val TR_ID = "H0STCNT0"
        const val NIGHT_FUTURES_TR_ID = "H0MFCNT0"
        const val SUBSCRIBE = "1"
        const val UNSUBSCRIBE = "2"
        const val REFRESH_INTERVAL_MILLIS = 10_000L
        val RECONNECT_DELAY: Duration = Duration.ofSeconds(5)

        /** 접속키 무효로 거절된 뒤 새 키로 다시 붙기까지. 접속키 발급을 5초마다 두드리지 않게 길게 잡는다. */
        val APPROVAL_RETRY_DELAY: Duration = Duration.ofSeconds(30)
    }
}

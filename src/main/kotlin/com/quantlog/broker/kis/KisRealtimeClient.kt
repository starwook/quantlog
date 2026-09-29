package com.quantlog.broker.kis

import com.fasterxml.jackson.databind.ObjectMapper
import com.quantlog.broker.Market
import com.quantlog.broker.MinuteCandle
import com.quantlog.chart.ChartBroadcaster
import com.quantlog.marketdata.MinuteCandleStore
import jakarta.annotation.PreDestroy
import mu.KotlinLogging
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
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
 * (3) 구독 중인 브라우저에 그대로 중계(ChartBroadcaster).
 * quantlog.kis.mock.realtime-enabled=false 면 아무것도 안 하고 기존 REST 폴링 방식 그대로 동작한다.
 */
@Component
class KisRealtimeClient(
    private val properties: KisProperties,
    private val tokenProvider: KisTokenProvider,
    private val candleStore: MinuteCandleStore,
    private val broadcaster: ChartBroadcaster,
    private val objectMapper: ObjectMapper,
) {
    private val httpClient = HttpClient.newHttpClient()
    private val liveQuotes = ConcurrentHashMap<String, LivePrice>()
    private val inProgress = ConcurrentHashMap<String, MutableCandle>()
    private var webSocket: WebSocket? = null

    fun latestPrice(symbol: String): LivePrice? = liveQuotes[symbol]

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        if (!properties.realtimeEnabled) return
        properties.requireCredentials()
        connect()
    }

    @PreDestroy
    fun stop() {
        webSocket?.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown")
    }

    private fun connect() {
        httpClient.newWebSocketBuilder()
            .buildAsync(URI.create(properties.wsUrl), Listener())
            .thenAccept { ws ->
                webSocket = ws
                properties.realtimeSymbols.forEach { subscribe(ws, it) }
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

    private fun subscribe(
        ws: WebSocket,
        symbol: String,
    ) {
        val message =
            objectMapper.writeValueAsString(
                mapOf(
                    "header" to
                        mapOf(
                            "approval_key" to tokenProvider.approvalKey(),
                            "custtype" to "P",
                            "tr_type" to "1",
                            "content-type" to "utf-8",
                        ),
                    "body" to mapOf("input" to mapOf("tr_id" to TR_ID, "tr_key" to symbol)),
                ),
            )
        ws.sendText(message, true)
    }

    private fun handle(raw: String) {
        if (raw.startsWith("0") || raw.startsWith("1")) {
            handleTick(raw)
            return
        }
        val node = objectMapper.readTree(raw)
        if (node.path("header").path("tr_id").asText() == "PINGPONG") {
            webSocket?.sendPong(ByteBuffer.wrap(raw.toByteArray()))
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
        broadcaster.broadcastCandle(Market.KR, symbol, current.toCandle())
    }

    private fun MutableCandle.toCandle() = MinuteCandle(LocalDate.now(KST), minute, open, high, low, close, volume)

    private inner class Listener : WebSocket.Listener {
        private val buffer = StringBuilder()

        override fun onOpen(webSocket: WebSocket) {
            log.info { "[실시간 시세] 연결됨" }
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
            log.warn { "[실시간 시세] 연결 종료(code=$statusCode, $reason) — ${RECONNECT_DELAY.seconds}초 후 재연결" }
            scheduleReconnect()
            return null
        }

        override fun onError(
            webSocket: WebSocket,
            error: Throwable,
        ) {
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
        const val SYMBOL_INDEX = 0
        const val TIME_INDEX = 1
        const val PRICE_INDEX = 2
        const val CNTG_VOL_INDEX = 12
        val RECONNECT_DELAY: Duration = Duration.ofSeconds(5)
        val HOUR_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HHmmss")
    }
}

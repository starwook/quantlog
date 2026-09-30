package com.quantlog.chart

import com.quantlog.broker.Market
import com.quantlog.marketdata.MarketDataService
import com.quantlog.position.TradeRepository
import com.quantlog.watchlist.SymbolStrategyService
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.ResponseBody
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

private val KST: ZoneId = ZoneId.of("Asia/Seoul")

/** 차트 라이브러리(lightweight-charts)가 요구하는 초 단위 timestamp. KST 벽시계 값을 그대로 UTC로 찍어서,
 * 브라우저의 기본(UTC) 포맷터가 그대로 한국 시각처럼 보이게 한다(별도 타임존 설정 없이 맞추는 트릭).
 * 화면은 항상 KST 기준이다 — 미국 분봉(거래소 현지 시각)은 [Market.zone]으로 KST 로 옮겨서 넘긴다. */
private fun epochSecondsAsIfUtc(dateTime: java.time.LocalDateTime): Long = dateTime.toEpochSecond(ZoneOffset.UTC)

data class CandlePoint(
    val time: Long,
    val open: BigDecimal,
    val high: BigDecimal,
    val low: BigDecimal,
    val close: BigDecimal,
    val volume: Long,
)

data class TradeMarker(
    val time: Long,
    val side: String,
    val price: BigDecimal,
    val quantity: Int,
)

data class ChartData(val candles: List<CandlePoint>, val trades: List<TradeMarker>)

/**
 * 분봉 차트 화면. marketdata(분봉)와 position(매매 기록) 두 도메인을 조합해서 보여주는 화면이라
 * 어느 한쪽 패키지에 넣지 않고 별도 패키지로 뺐다 (여러 도메인에 걸치는 화면 규칙).
 */
@Controller
class ChartController(
    private val marketDataService: MarketDataService,
    private val tradeRepository: TradeRepository,
    private val symbolStrategyService: SymbolStrategyService,
) {
    @GetMapping("/chart/{market}/{symbol}")
    fun page(
        @PathVariable market: Market,
        @PathVariable symbol: String,
        model: Model,
    ): String {
        model.addAttribute("market", market.name)
        model.addAttribute("symbol", symbol)
        model.addAttribute("symbolName", symbolStrategyService.displayName(market, symbol))
        model.addAttribute("watchedSymbols", symbolStrategyService.all())
        return "chart"
    }

    @GetMapping("/chart/{market}/{symbol}/data")
    @ResponseBody
    fun data(
        @PathVariable market: Market,
        @PathVariable symbol: String,
    ): ChartData {
        // DB 의 분봉 날짜는 거래소 현지 기준이라 "오늘"도 현지 날짜로 조회한다(KST 로 하면 미국장은 자정 이후 0건).
        // 화면에 찍는 시각만 KST 로 바꾼다.
        val zone = market.zone
        val today = Instant.now().atZone(zone).toLocalDate()
        val candles =
            marketDataService.recentCandles(market, symbol, today).map {
                CandlePoint(
                    time = epochSecondsAsIfUtc(it.date.atTime(it.time).atZone(zone).withZoneSameInstant(KST).toLocalDateTime()),
                    open = it.open,
                    high = it.high,
                    low = it.low,
                    close = it.close,
                    volume = it.volume,
                )
            }
        val trades =
            tradeRepository
                .findAllByMarketAndSymbolOrderByExecutedAtAsc(market, symbol)
                .filter { it.executedAt.atZone(zone).toLocalDate() == today }
                .map {
                    TradeMarker(
                        time = epochSecondsAsIfUtc(it.executedAt.atZone(KST).toLocalDateTime()),
                        side = it.side.name,
                        price = it.filledPrice ?: it.orderPrice,
                        quantity = it.quantity,
                    )
                }
        return ChartData(candles, trades)
    }
}

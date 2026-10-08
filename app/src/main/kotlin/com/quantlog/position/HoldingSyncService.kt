package com.quantlog.position

import com.quantlog.broker.FillNotice
import com.quantlog.broker.Holding
import com.quantlog.broker.Market
import com.quantlog.broker.Side
import com.quantlog.sync.SyncMismatchReporter
import com.quantlog.watchlist.SymbolStrategyService
import mu.KotlinLogging
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private val log = KotlinLogging.logger {}

/**
 * 보유 현황([AccountHolding]) = 앱이 기억하는 현재 수량·평단. 두 가지로 갱신한다.
 * - [applyFill]: 게이트웨이 원장(`broker_notice`)의 체결 줄을 [com.quantlog.gatewayclient.FillProjector] 가 순서대로 넘겨 주면 그만큼 수량·평단을 계산해 반영한다.
 * - [sync]: 게이트웨이의 잔고 스냅샷(증권사 값)으로 덮어쓴다(증권사가 정답). 어긋난 곳은 `[동기화 불일치]` 로 보고한다.
 */
@Service
class HoldingSyncService(
    private val accountHoldingRepository: AccountHoldingRepository,
    private val tradeRepository: TradeRepository,
    private val orderFills: OrderFills,
    private val symbolNames: SymbolStrategyService,
    private val events: ApplicationEventPublisher,
) : FillProgress {
    /** 마지막으로 잔고를 받기 시작한 시각. 한 번도 못 받았으면 null. */
    @Volatile
    private var lastSyncedAt: Instant? = null

    /** 종목별로 체결을 마지막으로 반영한 시각. 그보다 먼저 시작한 잔고 조회 결과로는 그 종목을 덮어쓰지 않는다. */
    private val fillAppliedAt = ConcurrentHashMap<Pair<Market, String>, Instant>()

    /**
     * 이 종목에 잔고 동기화보다 늦게 낸 주문이 있으면 true — 잔고 테이블이 그 주문을 아직 반영하지 못한 상태라
     * 스케줄러는 이 종목 판단(추가 매수·청산)을 다음 동기화까지 미룬다. 첫 동기화 전에도 true.
     */
    override fun hasUnsyncedTrade(
        market: Market,
        symbol: String,
    ): Boolean {
        val synced = lastSyncedAt ?: return true
        val last = tradeRepository.findFirstByMarketAndSymbolOrderByExecutedAtDesc(market, symbol) ?: return false
        // 체결을 이미 다 반영한 주문이면 동기화를 기다릴 필요가 없다.
        if (isOrderFilled(last.orderNo)) return false
        return last.executedAt.isAfter(synced)
    }

    /**
     * 이 주문의 체결을 주문수량만큼 다 반영했는가. 마틴게일의 "단계 진행 중"이 풀리는 기준이다. 원장(`broker_notice`) 중 앱이 반영을 끝낸 줄로 구해
     * 재시작해도 같고, 보유 현황에 아직 안 들어간 체결로 대기를 먼저 풀지 않는다.
     */
    override fun isOrderFilled(orderNo: String): Boolean {
        val trade = tradeRepository.findFirstByMarketAndOrderNo(Market.KR, orderNo) ?: return false
        return orderFills.projected(orderNo).total().quantity >= trade.quantity
    }

    /**
     * 원장(`broker_notice`)의 체결 통보 한 줄([noticeId])을 보유 현황에 반영한다. 체결 통보만 처리하고 접수 통보는 건너뛴다.
     * 매수는 수량 가중평균으로 평단을 다시 계산하고, 매도는 수량만 줄인다(0이 되면 행 삭제). 바뀐 내용 설명을 돌려준다(건너뛰면 null).
     * 부분체결 통보의 체결수량은 건별이다(2026-10-07 실측: 100주 매도가 44주 + 56주로 왔다). 그대로 더하고 빼되, 같은 통보가 중복 와도 주문수량을 넘기지 않게만 막는다.
     * [receivedAt] 은 게이트웨이가 통보를 받은 시각이다 — 앱이 늦게 반영해도 체결 시각은 이것이다.
     */
    @Transactional
    fun applyFill(
        notice: FillNotice,
        noticeId: Long,
        receivedAt: Instant,
        now: Instant = Instant.now(),
    ): String? {
        if (!notice.isFill) return null
        val side = notice.side ?: return null
        val price = notice.filledPrice ?: return null
        val reported = notice.filledQuantity?.toInt() ?: return null
        val quantity = reserveFillQuantity(notice.orderNo, noticeId, reported, orderQuantityOf(notice))
        if (quantity <= 0) return null

        val market = Market.KR
        val name = symbolNames.displayName(market, notice.symbol)
        val row = accountHoldingRepository.findByMarketAndSymbol(market, notice.symbol)
        // 매매 기록(TradeService)·화면에 알린다. 사본을 바꾸기 전의 평단을 실어 보내야 한다 — 매도 손익은 그 평단 기준으로 확정한다.
        // 사본에 종목이 없는 매도(row == null)도 체결은 실제로 일어났으니 알린다(평단은 null).
        events.publishEvent(
            FillAppliedEvent(market, notice.symbol, side, notice.orderNo, quantity, price, row?.avgCost, receivedAt, noticeId),
        )
        val change =
            when (side) {
                Side.BUY -> {
                    if (row == null) {
                        accountHoldingRepository.save(AccountHolding(market, notice.symbol, quantity, price, price))
                        "$name 신규 ${quantity}주 (체결 $price)"
                    } else {
                        val total = row.quantity + quantity
                        val average = row.avgCost.multiply(BigDecimal(row.quantity)).add(price.multiply(BigDecimal(quantity)))
                        row.update(total, average.divide(BigDecimal(total), COST_SCALE, RoundingMode.HALF_UP), price)
                        "$name ${row.quantity - quantity}주 → ${total}주 (체결 $price, 평단 ${row.avgCost.stripTrailingZeros().toPlainString()})"
                    }
                }
                Side.SELL -> {
                    if (row == null) {
                        SyncMismatchReporter.report(
                            AREA_FILL,
                            "$name(${notice.symbol}) 주문번호=${notice.orderNo}",
                            "보유 없음",
                            "${quantity}주 매도 체결 (${plain(price)})",
                            "반영 건너뜀 — 다음 KIS 잔고 동기화가 맞춘다",
                        )
                        return null
                    }
                    val left = row.quantity - quantity
                    if (left < 0) {
                        SyncMismatchReporter.report(
                            AREA_FILL,
                            "$name(${notice.symbol}) 주문번호=${notice.orderNo}",
                            "${row.quantity}주 보유",
                            "${quantity}주 매도 체결 (${plain(price)})",
                            "보유를 0주로 고침",
                        )
                    }
                    if (left <= 0) {
                        accountHoldingRepository.delete(row)
                        "$name 전량 매도 (${row.quantity}주 → 0주, 체결 $price)"
                    } else {
                        row.update(left, row.avgCost, price)
                        "$name ${row.quantity + quantity}주 → ${left}주 (체결 $price)"
                    }
                }
            }
        fillAppliedAt[market to notice.symbol] = now
        log.info { "[체결통보 반영] ${notice.orderNo} $change" }
        events.publishEvent(HoldingsChangedEvent)
        return change
    }

    /**
     * 주문수량. 체결통보가 주문수량을 0 으로 보내오는 경우가 있다(2026-10-07 실측: 쪼개져 온 매도 체결 두 건 모두 0 → 반영이 통째로 건너뛰어졌다).
     * 0 이하면 우리 매매 기록(Trade)의 수량을 쓰고, 그것도 없으면 상한 없이 체결수량을 그대로 믿는다.
     */
    private fun orderQuantityOf(notice: FillNotice): Int {
        val reported = notice.orderQuantity?.toInt() ?: 0
        if (reported > 0) return reported
        return tradeRepository.findFirstByMarketAndOrderNo(Market.KR, notice.orderNo)?.quantity ?: Int.MAX_VALUE
    }

    /**
     * 이 주문에서 이번 줄로 더 반영할 수량. 원장에서 이 줄 앞의 체결 합계가 이미 주문수량만큼이면 0 — 같은 통보가 중복 기록돼도,
     * 체결수량이 누적으로 와도 주문수량을 넘겨 더하지 않는다.
     */
    private fun reserveFillQuantity(
        orderNo: String,
        noticeId: Long,
        reported: Int,
        orderQuantity: Int,
    ): Int = minOf(reported, orderQuantity - orderFills.upTo(orderNo, noticeId - 1).total().quantity).coerceAtLeast(0)

    /**
     * [kis] 는 [fetchedAt] 시점에 받은 잔고 전체. 바뀐 내용 설명 목록을 돌려준다(변화가 없으면 빈 목록).
     * [fetchedAt] 은 KIS 를 부르기 **전** 시각이어야 한다 — 조회 도중 낸 주문을 "반영됨"으로 착각하지 않기 위해서다.
     */
    @Transactional
    fun sync(
        kis: List<Holding>,
        fetchedAt: Instant = Instant.now(),
    ): List<String> {
        fun nameOf(
            market: Market,
            symbol: String,
        ) = symbolNames.displayName(market, symbol)

        fun label(
            market: Market,
            symbol: String,
        ) = "${nameOf(market, symbol)}($symbol)"
        val actual = kis.filter { it.quantity.toInt() > 0 }.associateBy { it.market to it.symbol }
        val existing = accountHoldingRepository.findAll().associateBy { it.market to it.symbol }
        val changes = mutableListOf<String>()
        // 수량이 그대로여도 현재가가 바뀌면 화면(평가손익)이 갱신돼야 한다.
        var priceChanged = false

        // 이 조회를 시작한 뒤에 체결을 반영한 종목은 조회 결과가 체결 이전 값일 수 있어 이번 회차엔 건드리지 않는다(다음 회차가 맞춘다).
        fun reflectedAfterFetch(key: Pair<Market, String>) = fillAppliedAt[key]?.isAfter(fetchedAt) == true

        actual.filterKeys { !reflectedAfterFetch(it) }.forEach { (key, holding) ->
            val quantity = holding.quantity.toInt()
            val row = existing[key]
            if (row == null) {
                accountHoldingRepository.save(
                    AccountHolding(holding.market, holding.symbol, quantity, holding.averagePrice, holding.currentPrice),
                )
                changes += "${nameOf(holding.market, holding.symbol)} 신규 ${quantity}주 (평단 ${holding.averagePrice})"
                SyncMismatchReporter.report(
                    AREA_BALANCE,
                    label(holding.market, holding.symbol),
                    "보유 없음",
                    "${quantity}주 (평단 ${plain(holding.averagePrice)})",
                    "DB 에 추가",
                )
            } else {
                if (row.quantity != quantity) {
                    changes += "${nameOf(row.market, row.symbol)} ${row.quantity}주 → ${quantity}주 (평단 ${holding.averagePrice})"
                    SyncMismatchReporter.report(
                        AREA_BALANCE,
                        label(row.market, row.symbol),
                        "${row.quantity}주 (평단 ${plain(row.avgCost)})",
                        "${quantity}주 (평단 ${plain(holding.averagePrice)})",
                        "DB 를 증권사 값으로 고침",
                    )
                } else if (averageDiffers(row.avgCost, holding.averagePrice)) {
                    SyncMismatchReporter.report(
                        AREA_BALANCE,
                        label(row.market, row.symbol),
                        "평단 ${plain(row.avgCost)} (${quantity}주)",
                        "평단 ${plain(holding.averagePrice)} (${quantity}주)",
                        "DB 평단을 증권사 값으로 고침",
                    )
                }
                if (row.currentPrice.compareTo(holding.currentPrice) != 0) priceChanged = true
                row.update(quantity, holding.averagePrice, holding.currentPrice)
            }
        }
        existing.filterKeys { it !in actual && !reflectedAfterFetch(it) }.values.forEach {
            accountHoldingRepository.delete(it)
            changes += "${nameOf(it.market, it.symbol)} 잔고에서 사라짐 (${it.quantity}주)"
            SyncMismatchReporter.report(AREA_BALANCE, label(it.market, it.symbol), "${it.quantity}주", "보유 없음", "DB 에서 삭제")
        }

        lastSyncedAt = fetchedAt
        changes.forEach { log.info { "[잔고 동기화] $it" } }
        if (changes.isNotEmpty() || priceChanged) events.publishEvent(HoldingsChangedEvent)
        return changes
    }

    /**
     * 평단이 "어긋났다"고 볼 기준: 두 값의 차이가 큰 쪽의 0.01% 를 넘을 때. 체결가들로 직접 계산한 평단과 증권사가 주는 평단은
     * 소수 자릿수·반올림이 달라 미세하게 다를 수 있어서, 그 정도로는 매번 오류를 내지 않는다.
     */
    private fun averageDiffers(
        db: BigDecimal,
        kis: BigDecimal,
    ): Boolean {
        val base = db.abs().max(kis.abs())
        if (base.signum() == 0) return false
        return db.subtract(kis).abs().divide(base, MathContext.DECIMAL64) > AVERAGE_TOLERANCE
    }

    private fun plain(value: BigDecimal): String = value.stripTrailingZeros().toPlainString()

    private companion object {
        /** account_holding.avg_cost 의 소수 자릿수. */
        const val COST_SCALE = 6
        val AVERAGE_TOLERANCE: BigDecimal = BigDecimal("0.0001")
        const val AREA_BALANCE = "잔고 동기화"
        const val AREA_FILL = "체결통보 반영"
    }
}

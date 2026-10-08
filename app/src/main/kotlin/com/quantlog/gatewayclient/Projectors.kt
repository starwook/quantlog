package com.quantlog.gatewayclient

import com.fasterxml.jackson.databind.ObjectMapper
import com.quantlog.broker.FillNotice
import com.quantlog.broker.Holding
import com.quantlog.broker.KisBalanceParser
import com.quantlog.broker.KisFillNoticeParser
import com.quantlog.broker.KisMinuteChartParser
import com.quantlog.broker.Market
import com.quantlog.marketdata.MinuteCandleStore
import com.quantlog.position.HoldingSyncService
import com.quantlog.position.OrderFill
import com.quantlog.position.OrderFills
import com.quantlog.position.TradeService
import mu.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

private val log = KotlinLogging.logger {}

/** [FillProjector] 가 체결통보 원장(`kis_broker_fill`)을 어디까지 반영했는지 적는 커서 이름(`broker_projection_cursor.name`). 옛 `broker_fill` 용 커서 "fill" 과 섞이지 않게 이름을 바꿨다. */
private const val FILL_CURSOR = "kis_broker_fill"

/**
 * 게이트웨이가 원문 그대로 기록한 체결통보 원장(`kis_broker_fill`)을 한투 문서대로 읽어([KisFillNoticeParser]) 앱의 보유 현황(`account_holding`)·
 * 매매 기록(`trade`)에 반영한다. 어디까지 처리했는지는 `broker_projection_cursor` 에 둔다 — 앱이 꺼져 있던 동안 쌓인 통보도 재시작 후 그다음 줄부터
 * 이어서 처리한다(같은 통보 중복은 [HoldingSyncService.applyFill] 이 막는다). 체결 내역 자체는 원장에만 있고 앱은 사본을 따로 쓰지 않는다([OrderFills]).
 * 커서가 없으면(처음 켠 때) 지금 있는 마지막 ID 부터 시작한다 — 그 이전 체결은 잔고 스냅샷이 맞춘다.
 *
 * 동기화 불일치 보고 불필요: 증권사와 DB 를 비교하는 게 아니라 원장 행을 옮겨 적는 일이다. 불일치 보고는 [HoldingSyncService] 가 한다.
 */
@Component
class FillProjector(
    private val notices: KisBrokerFillRowRepository,
    private val cursors: ProjectionCursorRepository,
    private val holdingSync: HoldingSyncService,
    private val tradeService: TradeService,
    transactionManager: PlatformTransactionManager,
) {
    private val tx = TransactionTemplate(transactionManager)

    /** 처리한 행 수를 돌려준다. */
    @Synchronized
    fun project(): Int {
        var last = cursors.findById(CURSOR).map { it.lastId }.orElse(null)
        if (last == null) {
            last = notices.maxId() ?: 0
            cursors.save(ProjectionCursor(CURSOR, last))
            log.info { "[체결 반영] 커서 초기화: $last" }
            return 0
        }
        var processed = 0
        while (true) {
            val rows = notices.findTop100ByIdGreaterThanOrderByIdAsc(last!!)
            if (rows.isEmpty()) return processed
            rows.forEach { row ->
                project(row)
                last = row.id
                processed++
            }
        }
    }

    private fun project(row: KisBrokerFillRow) {
        val notice = KisFillNoticeParser.parse(row.trId, row.body)
        if (notice == null) {
            log.error { "[체결통보 해석 실패] 건너뛴다(다음 잔고 반영이 보유를 바로잡는다): kis_broker_fill.id=${row.id} ${row.trId} ${row.body}" }
            cursors.save(ProjectionCursor(CURSOR, row.id!!))
            return
        }
        // 한 행이 실패해도 다음 행을 막지 않는다 — 틀린 부분은 다음 잔고 스냅샷 반영이 바로잡는다.
        runCatching {
            tx.executeWithoutResult {
                // 체결은 보유 현황에 반영하고(매매 기록은 그 반영 이벤트로 따라온다), 접수 통보는 "증권사가 받은 미체결 주문"으로 표시한다.
                if (notice.isFill) holdingSync.applyFill(notice, row.id!!, row.receivedAt) else tradeService.onOrderNotice(notice)
                cursors.save(ProjectionCursor(CURSOR, row.id!!))
            }
        }.onFailure {
            // 건너뛴 체결을 나중에 찾아볼 수 있게 ERROR 로 남긴다(/errors·웹훅). 원장 행 ID(kis_broker_fill.id)와 주문번호로 그 행을 특정한다.
            log.error(it) { "[체결 반영 실패] 건너뛴다(다음 잔고 반영이 보유를 바로잡는다): kis_broker_fill.id=${row.id} ${notice.summary()}" }
            cursors.save(ProjectionCursor(CURSOR, row.id!!))
        }
    }

    private companion object {
        const val CURSOR = FILL_CURSOR
    }
}

/**
 * [OrderFills] 를 게이트웨이 원장(`kis_broker_fill`)에서 읽는다. "반영된" 줄은 [FillProjector] 의 커서까지다.
 * 원장은 추가만 되고 바뀌지 않으므로, 한 번 읽어 해석한 줄은 메모리에 두고 새 줄만 더 읽는다(주문번호가 원문 안에 있어 DB 로 걸러 읽을 수 없다).
 */
@Component
class KisBrokerFillOrderFills(
    private val notices: KisBrokerFillRowRepository,
    private val cursors: ProjectionCursorRepository,
) : OrderFills {
    private class Parsed(val id: Long, val receivedAt: Instant, val notice: FillNotice)

    private val byOrder = HashMap<String, MutableList<Parsed>>()
    private val all = ArrayList<Parsed>()
    private var loadedUpTo = 0L

    override fun upTo(
        orderNo: String,
        lastId: Long,
    ): List<OrderFill> = ofOrder(orderNo).filter { it.id <= lastId }.mapNotNull { it.toFill() }

    override fun projected(orderNo: String): List<OrderFill> = upTo(orderNo, projectedUpTo())

    override fun projectedAll(): List<OrderFill> {
        val upTo = projectedUpTo()
        return synchronized(this) {
            load()
            all.filter { it.id <= upTo }
        }.mapNotNull { it.toFill() }
    }

    override fun accepted(orderNo: String): Boolean {
        val upTo = projectedUpTo()
        return ofOrder(orderNo).any {
            it.id <= upTo && !it.notice.isFill && (it.notice.refuseFlag.isBlank() || it.notice.refuseFlag == NOT_REFUSED)
        }
    }

    @Synchronized
    private fun ofOrder(orderNo: String): List<Parsed> {
        load()
        return byOrder[orderNo].orEmpty().toList()
    }

    /** 아직 안 읽은 원장 줄을 읽어 해석해 둔다. 해석할 수 없는 줄은 건너뛴다([FillProjector] 가 ERROR 로 알린다). */
    private fun load() {
        while (true) {
            val rows = notices.findTop100ByIdGreaterThanOrderByIdAsc(loadedUpTo)
            if (rows.isEmpty()) return
            rows.forEach { row ->
                KisFillNoticeParser.parse(row.trId, row.body)?.let { notice ->
                    val parsed = Parsed(row.id!!, row.receivedAt, notice)
                    all += parsed
                    byOrder.getOrPut(notice.orderNo) { mutableListOf() } += parsed
                }
                loadedUpTo = row.id!!
            }
        }
    }

    /** 지금까지 반영을 끝낸 마지막 원장 ID. 커서가 아직 없으면 0. */
    private fun projectedUpTo(): Long = cursors.findById(FILL_CURSOR).map { it.lastId }.orElse(0)

    /** 체결 통보만 [OrderFill] 로 바꾼다. 접수 통보이거나 값이 빠진 줄은 null. */
    private fun Parsed.toFill(): OrderFill? {
        if (!notice.isFill) return null
        val side = notice.side ?: return null
        val quantity = notice.filledQuantity?.toInt()?.takeIf { it > 0 } ?: return null
        val price = notice.filledPrice ?: return null
        return OrderFill(id, Market.KR, notice.symbol, side, notice.orderNo, quantity, price, receivedAt)
    }

    private companion object {
        /** RFUS_YN: 정상 접수면 "0". */
        const val NOT_REFUSED = "0"
    }
}

/**
 * 게이트웨이가 원문 그대로 쌓은 잔고(`kis_balance`)의 가장 최근 줄이 새 것이면 한투 문서대로 읽어([KisBalanceParser]) 보유 현황에 맞춘다.
 * 앱이 꺼져 있던 동안 쌓인 줄은 중간 것을 건너뛰어도 된다(각 줄이 그 시점의 전체 잔고). 읽을 수 없는 줄은 ERROR 로 알리고 건너뛴다.
 * 맞춘 뒤 보유 종목을 `held_symbol` 로 알려 게이트웨이가 그 종목의 실시간 시세를 먼저 구독하게 한다.
 */
@Component
class BalanceProjector(
    private val balances: KisBalanceRowRepository,
    private val cursors: ProjectionCursorRepository,
    private val holdingSync: HoldingSyncService,
    private val heldSymbols: HeldSymbolPublisher,
    private val objectMapper: ObjectMapper,
) {
    /** 반영했으면 true. */
    @Synchronized
    fun project(): Boolean {
        val row = balances.findTopByOrderByIdDesc() ?: return false
        val applied = cursors.findById(CURSOR).map { it.lastId }.orElse(0)
        if (row.id!! <= applied) return false
        val holdings =
            runCatching { KisBalanceParser.parse(objectMapper.readTree(row.body)) }.getOrElse {
                log.error(it) { "[잔고 해석 실패] 건너뛴다(다음 회차가 맞춘다): kis_balance.id=${row.id} ${row.body.take(300)}" }
                cursors.save(ProjectionCursor(CURSOR, row.id!!))
                return false
            }
        holdingSync.sync(holdings, row.fetchedAt)
        heldSymbols.publish(holdings)
        cursors.save(ProjectionCursor(CURSOR, row.id!!))
        return true
    }

    private companion object {
        /** 옛 `broker_balance_meta` 회차 번호용 커서 "balance" 와 섞이지 않게 이름을 바꿨다. */
        const val CURSOR = "kis_balance"
    }
}

/** 보유 종목을 게이트웨이에 알리는 계약 테이블(`held_symbol`)을 지금 보유 종목과 같게 맞춘다. */
@Component
class HeldSymbolPublisher(
    private val rows: HeldSymbolRowRepository,
) {
    @Transactional
    fun publish(holdings: List<Holding>) {
        val wanted = holdings.map { it.market.name to it.symbol }.toSet()
        val existing = rows.findAll().associateBy { it.market to it.symbol }
        existing.filterKeys { it !in wanted }.values.forEach { rows.delete(it) }
        (wanted - existing.keys).forEach { (market, symbol) -> rows.save(HeldSymbolRow(market, symbol)) }
    }
}

/**
 * 게이트웨이가 원문 그대로 쌓은 분봉 응답(`kis_minute_chart`)을 한투 문서대로 읽어([KisMinuteChartParser]) 앱의 `minute_candle` 에 넣는다.
 * 줄마다 그 종목의 최근 30건이 들어 있어 한 번에 읽은 줄 중 종목별 **가장 최근 줄**만 풀어도 빠지는 분봉이 없다. 이미 있는 분봉은 저장하지 않는다(실시간 합성분과 겹쳐도 된다).
 */
@Component
class CandleProjector(
    private val charts: KisMinuteChartRowRepository,
    private val cursors: ProjectionCursorRepository,
    private val store: MinuteCandleStore,
    private val objectMapper: ObjectMapper,
    private val properties: GatewayClientProperties,
) {
    @Scheduled(fixedDelay = 1_000, initialDelay = 10_000)
    fun run() {
        if (!properties.enabled) return
        runCatching { project() }.onFailure { log.warn(it) { "[분봉 반영] 원문 읽기 실패, 다음 회차에 재시도: ${it.message}" } }
    }

    /** 처리한 줄 수를 돌려준다. */
    @Synchronized
    fun project(): Int {
        var last = cursors.findById(CURSOR).map { it.lastId }.orElse(0)
        var processed = 0
        while (true) {
            val rows = charts.findTop200ByIdGreaterThanOrderByIdAsc(last)
            if (rows.isEmpty()) return processed
            rows.groupBy { it.market to it.symbol }.values.map { it.last() }.forEach { row ->
                runCatching {
                    val market = Market.valueOf(row.market)
                    KisMinuteChartParser.parse(objectMapper.readTree(row.body)).forEach { store.saveIfNew(market, row.symbol, it) }
                }.onFailure { log.error(it) { "[분봉 해석 실패] 건너뛴다: kis_minute_chart.id=${row.id} ${row.body.take(300)}" } }
            }
            last = rows.last().id!!
            processed += rows.size
            cursors.save(ProjectionCursor(CURSOR, last))
        }
    }

    private companion object {
        const val CURSOR = "kis_minute_chart"
    }
}

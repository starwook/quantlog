package com.quantlog.futures

import com.quantlog.broker.KisApiException
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.util.concurrent.atomic.AtomicBoolean

private val log = KotlinLogging.logger {}

/** 그날·그 월물의 저장본을 통째로 바꿔 넣는다(같은 날 다시 모으면 덮어쓴다). */
@Component
class OptionSnapshotStore(private val repository: OptionOpenInterestRepository) {
    @Transactional
    fun replace(
        tradeDate: LocalDate,
        maturity: YearMonth,
        underlying: BigDecimal,
        strikes: List<OptionStrike>,
        collectedAt: LocalDateTime,
    ) {
        repository.deleteSnapshot(tradeDate, maturity.toString())
        repository.flush()
        repository.saveAll(strikes.map { OptionOpenInterest.of(tradeDate, maturity.toString(), underlying, it, collectedAt) })
    }

    /** 그 월물의 가장 최근 저장본. 없으면 null. */
    @Transactional(readOnly = true)
    fun latest(maturity: YearMonth): Pair<LocalDate, OptionBoard>? {
        val date = repository.latestTradeDate(maturity.toString()) ?: return null
        val rows = repository.findAllByTradeDateAndMaturity(date, maturity.toString())
        return date to
            OptionBoard(
                rows.map {
                    it.toStrike()
                },
                rows.firstNotNullOfOrNull { it.underlying }, rows.firstNotNullOfOrNull { it.collectedAt },
            )
    }
}

/**
 * KOSPI200 지수 위아래 [RANGE] 안의 옵션 행사가를 하나씩 조회해 하루치 매물대 저장본을 만든다.
 * 행사가마다 콜·풋 두 번씩 부르므로 한투 호출이 100번 안팎이고 5분쯤 걸린다(2026-10-13 실측: 116회 287초) — 그래서 화면을 열 때마다 부르지 않고 저장해 둔다.
 * 쉬지 않고 이어 부르면 한투 초당 한도(EGW00201)에 걸려 게이트웨이의 잔고 조회까지 밀리므로, 호출 사이에 [pauseMillis] 만큼 쉬고 한도 초과는 기다렸다 다시 부른다.
 */
@Component
class OptionChainCollector(
    private val client: FuturesOptionsClient,
    private val store: OptionSnapshotStore,
    private val clock: Clock,
    @Value("\${quantlog.futures.collect-pause-millis:500}") private val pauseMillis: Long,
) {
    private val running = AtomicBoolean(false)

    val isRunning: Boolean get() = running.get()

    /** [collect] 를 뒤에서 돌린다(몇 분 걸려 화면 요청이 기다리지 않게). 이미 모으는 중이면 false. */
    fun collectInBackground(maturity: YearMonth): Boolean {
        if (isRunning) return false
        Thread({
            runCatching { collect(maturity) }
                .onSuccess { log.info { "[옵션 매물대] $maturity 월물 행사가 ${it}개 저장" } }
                .onFailure { log.warn { "[옵션 매물대] $maturity 수집 실패: ${it.message}" } }
        }, "option-chain-collector").start()
        return true
    }

    /** 모아서 저장하고 행사가 수를 돌려준다. 이미 모으는 중이면 [IllegalStateException]. */
    fun collect(maturity: YearMonth): Int {
        check(running.compareAndSet(false, true)) { "이미 수집 중입니다" }
        try {
            // 순번 1번(행사가 1000.0) 콜로 지수를 먼저 얻는다. 지수가 1000 에서 아주 멀어져 이 종목이 없어지면 여기를 고쳐야 한다.
            val first = ask(OptionCode.from1000(true, maturity, 1)) ?: error("$maturity 월물 옵션이 없습니다")
            val underlying = first.underlying ?: error("KOSPI200 지수를 받지 못했습니다")
            val low = underlying * (BigDecimal.ONE - RANGE)
            val high = underlying * (BigDecimal.ONE + RANGE)
            val strikes = below1000(maturity, low, high) + from1000(maturity, low, high)
            store.replace(LocalDate.now(clock), maturity, underlying, strikes, LocalDateTime.now(clock))
            return strikes.size
        } finally {
            running.set(false)
        }
    }

    private fun below1000(
        maturity: YearMonth,
        low: BigDecimal,
        high: BigDecimal,
    ): List<OptionStrike> =
        generateSequence(low.divide(STEP, 0, RoundingMode.CEILING) * STEP) { it + STEP }
            .takeWhile { it <= high && it < OptionCode.THOUSAND }
            .mapNotNull { strike ->
                val call = ask(OptionCode.below1000(true, maturity, strike))
                val put = ask(OptionCode.below1000(false, maturity, strike))
                (call ?: put)?.let { OptionStrike(it.strike, call?.quote, put?.quote) }
            }.toList()

    /** 1000 이상은 코드가 순번이라 행사가를 응답에서 읽으며 올라간다. */
    private fun from1000(
        maturity: YearMonth,
        low: BigDecimal,
        high: BigDecimal,
    ): List<OptionStrike> {
        if (high < OptionCode.THOUSAND) return emptyList()
        val strikes = mutableListOf<OptionStrike>()
        for (order in 1..MAX_ORDER) {
            val call = ask(OptionCode.from1000(true, maturity, order)) ?: break
            if (call.strike > high) break
            if (call.strike < low) continue
            val put = ask(OptionCode.from1000(false, maturity, order))
            strikes += OptionStrike(call.strike, call.quote, put?.quote)
        }
        return strikes
    }

    private fun ask(code: String): OptionPrice? {
        repeat(LIMIT_RETRIES) {
            Thread.sleep(pauseMillis)
            try {
                return client.optionPrice(code)
            } catch (e: KisApiException) {
                if (e.code != RATE_LIMITED) throw e
                Thread.sleep(pauseMillis * LIMIT_BACKOFF)
            }
        }
        error("한투 초당 한도 초과가 이어져 중단했습니다 ($code)")
    }

    private companion object {
        /** 한투 초당 거래건수 초과. */
        const val RATE_LIMITED = "EGW00201"
        const val LIMIT_RETRIES = 5

        /** 한도 초과 뒤에는 평소 간격의 이만큼 더 쉰다. */
        const val LIMIT_BACKOFF = 4

        /** 지수 위아래로 모으는 범위(±8%). 매물대는 지수 근처가 의미 있고, 넓히면 호출 수가 늘어난다. */
        val RANGE = BigDecimal("0.08")

        /** 1000 미만 행사가 간격. */
        val STEP = BigDecimal("2.5")

        /** 1000 이상에서 올라가 볼 최대 순번(끝없이 부르지 않게). */
        const val MAX_ORDER = 300
    }
}

/**
 * 평일 16:00(옵션 정규장 15:45 마감 뒤)에 가장 가까운 월물의 매물대를 모은다. 만기일 다음 영업일에는 가장 가까운 월물이 다음 달로 바뀌는데
 * 그 월물은 저장본이 아직 없어 화면이 비므로, 평일 08:30 에 저장본이 하나도 없으면 한 번 모아 둔다(월물 바꾸기를 사람이 누르지 않게).
 * `quantlog.futures.collect-enabled=false` 면 끈다.
 */
@Component
@ConditionalOnProperty(prefix = "quantlog.futures", name = ["collect-enabled"], havingValue = "true", matchIfMissing = true)
class OptionChainScheduler(
    private val collector: OptionChainCollector,
    private val store: OptionSnapshotStore,
    private val clock: Clock,
) {
    @Scheduled(cron = "0 0 16 * * MON-FRI", zone = "Asia/Seoul")
    fun collect() {
        collector.collectInBackground(OptionMaturity.nearest(LocalDate.now(clock)))
    }

    @Scheduled(cron = "0 30 8 * * MON-FRI", zone = "Asia/Seoul")
    fun collectIfMissing() {
        val month = OptionMaturity.nearest(LocalDate.now(clock))
        if (store.latest(month) == null) collector.collectInBackground(month)
    }
}

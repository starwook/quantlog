package com.quantlog.position

import com.quantlog.broker.BrokerClient
import com.quantlog.watchlist.SymbolStrategyService
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private val log = KotlinLogging.logger {}

/** [stale] 이 true 면 이번 조회는 실패해서 마지막으로 성공한 값을 보여주는 것이다. */
data class OrderableCash(val amount: BigDecimal, val stale: Boolean)

/**
 * 통화별 주문 가능 금액(주문가능현금/외화주문가능금액). 화면용이다.
 * KIS 에 "예수금만" 묻는 BrokerClient 메서드가 없어서 [BrokerClient.buyingPower] 를 재사용한다 — 이 API 는 종목·가격이
 * 필요해서, 그 통화로 등록된 첫 종목과 그 현재가를 기준으로 묻는다. 주문 가능 금액은 종목과 무관한 계좌 값이라 어느 종목이든 같다.
 * 화면을 열 때마다 KIS 를 부르면 매매 스케줄러와 초당 호출 한도를 나눠 쓰게 되므로 [TTL] 동안은 캐시를 쓴다.
 */
@Service
class CashService(
    private val broker: BrokerClient,
    private val symbolStrategyService: SymbolStrategyService,
) {
    private class Cached(val amount: BigDecimal, val at: Instant)

    private val cache = ConcurrentHashMap<String, Cached>()

    fun orderableCash(currency: String): OrderableCash? = orderableCash(currency, Instant.now())

    /** 통화에 해당하는 종목이 없거나 조회가 실패했는데 캐시도 없으면 null. [now] 는 캐시 만료 판정용(테스트에서 시각을 넣는다). */
    fun orderableCash(
        currency: String,
        now: Instant,
    ): OrderableCash? {
        val cached = cache[currency]
        if (cached != null && Duration.between(cached.at, now) < TTL) return OrderableCash(cached.amount, stale = false)

        val reference = symbolStrategyService.all().firstOrNull { it.market.currency == currency } ?: return null
        return runCatching {
            val price = broker.quote(reference.market, reference.symbol).price
            broker.buyingPower(reference.market, reference.symbol, price).orderableAmount
        }.onSuccess { cache[currency] = Cached(it, now) }
            .map { OrderableCash(it, stale = false) }
            .getOrElse {
                log.warn(it) { "[주문 가능 금액] 조회 실패: $currency" }
                cached?.let { last -> OrderableCash(last.amount, stale = true) }
            }
    }

    private companion object {
        val TTL: Duration = Duration.ofSeconds(30)
    }
}

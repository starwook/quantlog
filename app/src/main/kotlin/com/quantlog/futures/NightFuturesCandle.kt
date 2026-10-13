package com.quantlog.futures

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.springframework.data.jpa.repository.JpaRepository
import java.math.BigDecimal
import java.time.LocalDateTime

/** 야간선물 실시간 체결(`H0MFCNT0`)을 1분 단위로 묶은 봉. 한투 REST 분봉은 야간을 주지 않아 앱이 직접 쌓는다. */
@Entity
@Table(
    name = "night_futures_candle",
    uniqueConstraints = [UniqueConstraint(columnNames = ["code", "minute_start"])],
)
class NightFuturesCandle(
    @Column(nullable = false, length = 20)
    val code: String,
    @Column(name = "minute_start", nullable = false)
    val minuteStart: LocalDateTime,
    @Column(nullable = false, precision = 10, scale = 2)
    var open: BigDecimal,
    @Column(nullable = false, precision = 10, scale = 2)
    var high: BigDecimal,
    @Column(nullable = false, precision = 10, scale = 2)
    var low: BigDecimal,
    @Column(nullable = false, precision = 10, scale = 2)
    var close: BigDecimal,
    @Column(nullable = false)
    var volume: Long,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    /** 같은 분의 다음 체결을 반영한다. */
    fun add(
        price: BigDecimal,
        qty: Long,
    ) {
        high = high.max(price)
        low = low.min(price)
        close = price
        volume += qty
    }

    fun toCandle() =
        Candle(
            date = minuteStart.toLocalDate().toString().replace("-", ""),
            time = "%02d%02d00".format(minuteStart.hour, minuteStart.minute),
            open = open,
            high = high,
            low = low,
            close = close,
            volume = volume,
        )
}

interface NightFuturesCandleRepository : JpaRepository<NightFuturesCandle, Long> {
    fun findByCodeAndMinuteStart(
        code: String,
        minuteStart: LocalDateTime,
    ): NightFuturesCandle?

    fun findTop300ByCodeOrderByMinuteStartDesc(code: String): List<NightFuturesCandle>
}

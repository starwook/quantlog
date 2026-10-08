package com.quantlog.marketdata

import com.quantlog.broker.Market
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

/**
 * 분봉 1건 저장. KIS 는 당일 분봉만 주고 그나마도 재조회하면 없어질 수 있어(장 마감 후 등),
 * 나중에 패턴 분석에 쓰려면 받는 족족 우리 DB에 쌓아야 한다 (2026-09-29 사용자 결정).
 */
@Entity
@Table(
    name = "minute_candle",
    uniqueConstraints = [
        UniqueConstraint(columnNames = ["market", "symbol", "trade_date", "trade_time"]),
    ],
)
class MinuteCandleEntity(
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val market: Market,
    @Column(nullable = false, length = 20)
    val symbol: String,
    @Column(name = "trade_date", nullable = false)
    val tradeDate: LocalDate,
    @Column(name = "trade_time", nullable = false)
    val tradeTime: LocalTime,
    @Column(nullable = false, precision = 19, scale = 4)
    val open: BigDecimal,
    @Column(nullable = false, precision = 19, scale = 4)
    val high: BigDecimal,
    @Column(nullable = false, precision = 19, scale = 4)
    val low: BigDecimal,
    @Column(nullable = false, precision = 19, scale = 4)
    val close: BigDecimal,
    @Column(nullable = false)
    val volume: Long,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set
}

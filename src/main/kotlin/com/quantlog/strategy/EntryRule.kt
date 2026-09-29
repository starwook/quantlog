package com.quantlog.strategy

import com.quantlog.broker.MinuteCandle
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.math.BigDecimal
import java.math.RoundingMode

enum class EntrySignal { BUY, NO_TRADE }

/**
 * 저점 근처 반등 진입 신호의 초기값. 아직 검증 전 가설이라 playbook/principles.md에는
 * "아직 정하는 중"으로 등록돼 있다 — 모의투자 로그가 쌓이면 값을 스윕해서 확정한다.
 * (2026-09-29: 모멘텀+거래량 돌파 → 당일 최저가 근접+반등 → **체류시간 기반 지지선**으로 두 번 전환.
 * 하루 딱 한 번 찍은 최저가는 우연일 수 있다 — 오늘 하루 중 저가권에서 가장 오래 머문 가격대를
 * "많이 테스트받고 버틴 지지선"으로 보고 그 근처에서 반등하면 산다.)
 */
@ConfigurationProperties(prefix = "quantlog.strategy.entry")
data class EntryStrategyProperties(
    /** 이만큼 분봉이 쌓이기 전엔 지지선이 의미가 없다고 보고 매수하지 않는다(장 시작 직후 오탐 방지). */
    val minCandles: Int = 15,
    /** 지지선 대비 이 % 이내면 "지지선 근처"로 본다. */
    val nearSupportPercent: BigDecimal = BigDecimal("0.3"),
    /** 당일 (고가-저가) 범위를 이만큼의 구간으로 쪼개서 각 구간에 분봉이 몇 개 머물렀는지 센다. */
    val supportBucketCount: Int = 20,
    /** 저가부터 이 비율만큼의 구간에서만 "가장 많이 머문 구간"을 찾는다(고가권 눌림을 지지선으로 잘못 잡지 않게). */
    val supportZoneFraction: BigDecimal = BigDecimal("0.5"),
)

/**
 * 당일 저가권에서 가장 오래 머문 가격대(체류시간 기준 지지선) 근처까지 내려왔다가 직전 분봉보다
 * 오른(반등 시작) 분봉이면 BUY. 근거가 될 만큼 분봉이 안 쌓였으면 NO_TRADE
 * ("근거 없으면 안 산다", playbook/principles.md).
 */
class SupportBounceEntryRule(
    private val properties: EntryStrategyProperties,
) {
    /** candlesAscending 은 오래된 것부터 최신 순이어야 한다. */
    fun evaluate(candlesAscending: List<MinuteCandle>): EntrySignal {
        if (candlesAscending.size < properties.minCandles) return EntrySignal.NO_TRADE

        val support = supportLevel(candlesAscending)
        val latest = candlesAscending.last()
        val previous = candlesAscending[candlesAscending.size - 2]

        val distanceFromSupportPercent =
            latest.close
                .subtract(support)
                .divide(support, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal(100))
                .abs()
        val bouncing = latest.close > previous.close

        return if (distanceFromSupportPercent <= properties.nearSupportPercent && bouncing) {
            EntrySignal.BUY
        } else {
            EntrySignal.NO_TRADE
        }
    }

    /**
     * 당일 (고가-저가) 구간을 supportBucketCount 개로 쪼개서, 저가권(supportZoneFraction 이내)에서
     * 종가가 가장 많이 찍힌 구간의 중간값을 지지선으로 본다. 하루 종일 거의 안 움직였으면(구간폭 0)
     * 그냥 당일 최저가를 쓴다.
     */
    private fun supportLevel(candles: List<MinuteCandle>): BigDecimal {
        val dayHigh = candles.maxOf { it.high }
        val dayLow = candles.minOf { it.low }
        val range = dayHigh.subtract(dayLow)
        if (range <= BigDecimal.ZERO) return dayLow

        val bucketWidth = range.divide(BigDecimal(properties.supportBucketCount), 6, RoundingMode.HALF_UP)
        val zoneBucketLimit =
            (properties.supportBucketCount * properties.supportZoneFraction.toDouble())
                .toInt()
                .coerceIn(1, properties.supportBucketCount)

        val counts = IntArray(properties.supportBucketCount)
        candles.forEach { candle ->
            val bucket =
                candle.close
                    .subtract(dayLow)
                    .divide(bucketWidth, 0, RoundingMode.DOWN)
                    .toInt()
                    .coerceIn(0, properties.supportBucketCount - 1)
            counts[bucket]++
        }

        val bestBucket = (0 until zoneBucketLimit).maxByOrNull { counts[it] } ?: 0
        return dayLow.add(bucketWidth.multiply(BigDecimal(bestBucket).add(BigDecimal("0.5"))))
    }
}

@Configuration
class EntryStrategyConfig {
    @Bean
    fun supportBounceEntryRule(properties: EntryStrategyProperties) = SupportBounceEntryRule(properties)
}

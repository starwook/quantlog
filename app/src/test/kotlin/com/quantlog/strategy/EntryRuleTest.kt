package com.quantlog.strategy

import com.quantlog.broker.MinuteCandle
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.assertEquals

class EntryRuleTest {
    private val properties =
        EntryStrategyProperties(
            minCandles = 15,
            nearSupportPercent = BigDecimal("0.3"),
            supportBucketCount = 20,
            supportZoneFraction = BigDecimal("0.5"),
        )
    private val rule = SupportBounceEntryRule(properties)
    private val date = LocalDate.of(2026, 9, 29)

    private fun candle(
        minute: Int,
        low: String,
        high: String,
        close: String,
    ) = MinuteCandle(
        date = date,
        time = LocalTime.of(9, 0).plusMinutes(minute.toLong()),
        open = BigDecimal(close),
        high = BigDecimal(high),
        low = BigDecimal(low),
        close = BigDecimal(close),
        volume = 100,
    )

    /**
     * 하루 범위 9,700~10,200원. 9,900원대(저가권)에서 10번 머물다(=지지선), 딱 한 번 9,700원까지
     * 튀었다가(우연한 저점), 10,200원까지 올랐다 내려온다. "많이 머문 9,900원대"와 "한 번 튄 9,700원"을
     * 구분할 수 있는지가 이 테스트의 핵심이다.
     */
    private fun baseline(): MutableList<MinuteCandle> {
        val candles = mutableListOf<MinuteCandle>()
        repeat(10) { candles += candle(it, "9880", "9900", "9900") } // 0~9: 9,900원대 체류 (지지선 후보)
        candles += candle(10, "9700", "9750", "9750") // 10: 한 번뿐인 저점 스파이크
        candles += candle(11, "10000", "10200", "10200") // 11: 당일 고가
        candles += candle(12, "10100", "10150", "10120")
        candles += candle(13, "9950", "10050", "9980")
        candles += candle(14, "9880", "9900", "9895") // 14: 9,900원대로 복귀
        return candles
    }

    @Test
    fun `많이 머문 지지선 근처에서 반등하면 매수 - 한 번뿐인 저점은 무시한다`() {
        val candles = baseline()
        candles += candle(15, "9880", "9915", "9910") // 지지선(9900원대) 근처, 직전(9895)보다 상승

        assertEquals(EntrySignal.BUY, rule.evaluate(candles))
    }

    @Test
    fun `한 번뿐인 저점 근처에서 반등해도 매수 안 함`() {
        val candles = baseline()
        // 9,700원대(한 번뿐인 스파이크) 근처로 다시 와서 반등해도, 지지선(9900원대)과는 거리가 멀다.
        candles += candle(15, "9700", "9760", "9755")

        assertEquals(EntrySignal.NO_TRADE, rule.evaluate(candles))
    }

    @Test
    fun `지지선 근처지만 아직 반등이 아니면 매수 안 함`() {
        val candles = baseline()
        candles += candle(15, "9870", "9895", "9890") // 지지선(9912.5)과 0.3% 이내지만, 직전(9895)보다 내려감 — 반등 아님

        assertEquals(EntrySignal.NO_TRADE, rule.evaluate(candles))
    }

    @Test
    fun `분봉이 minCandles만큼 안 쌓였으면 매수 안 함`() {
        val candles = (0..5).map { candle(it, "9800", "9850", "9820") }

        assertEquals(EntrySignal.NO_TRADE, rule.evaluate(candles))
    }

    @Test
    fun `하루 범위가 아주 좁아도 지지선 근처 반등이면 매수한다`() {
        val flat = (0..13).map { candle(it, "9990", "10000", "9995") }.toMutableList()
        flat += candle(14, "9990", "9998", "9992")
        flat += candle(15, "9990", "9999", "9993") // 좁은 범위 안에서도 지지선 근처, 직전(9992)보다 상승

        assertEquals(EntrySignal.BUY, rule.evaluate(flat))
    }

    @Test
    fun `당일 고가-저가 범위가 완전히 0이어도 나눗셈 예외 없이 매수 안 함으로 처리한다`() {
        // 하루 종일 가격이 단 한 번도 안 바뀌면 range=0 — bucketWidth 나눗셈에서 예외가 나면 안 된다.
        // (참고: range=0이려면 반등도 없다는 뜻이라 이 경우 BUY 는 애초에 나올 수 없다.)
        val flat = (0..15).map { candle(it, "9990", "9990", "9990") }

        assertEquals(EntrySignal.NO_TRADE, rule.evaluate(flat))
    }
}

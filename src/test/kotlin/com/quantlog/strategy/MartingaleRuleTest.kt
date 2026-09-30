package com.quantlog.strategy

import com.quantlog.broker.Market
import com.quantlog.broker.Quote
import com.quantlog.broker.Side
import com.quantlog.position.Trade
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MartingaleRuleTest {
    private val rule = MartingaleRule(MartingaleProperties())

    private fun krw(price: String) = Quote(BigDecimal(price), BigDecimal("500"))

    private fun trade(
        side: Side,
        quantity: Int,
        price: String,
        filled: String? = price,
    ) = Trade(Market.KR, "005930", side, quantity, BigDecimal(price), "0", "ok", initialFilledPrice = filled?.let { BigDecimal(it) })

    private fun cycleOf(vararg buys: Pair<Int, String>) = MartingaleCycle(buys.map { CycleBuy(it.first, BigDecimal(it.second)) }, null)

    private fun price(value: BigDecimal?) = value?.stripTrailingZeros()?.toPlainString()

    // ── 사이클 계산 ──

    @Test
    fun `단계는 마지막 SELL 이후 BUY 건수`() {
        val cycle =
            MartingaleCycle.from(
                listOf(
                    trade(Side.BUY, 1, "272000"),
                    trade(Side.SELL, 1, "273500"),
                    trade(Side.BUY, 1, "272000"),
                    trade(Side.BUY, 2, "270500"),
                ),
            )
        assertEquals(2, cycle.stage)
        assertEquals(listOf(1, 2), cycle.buys.map { it.quantity })
    }

    @Test
    fun `직전 매수가는 체결가 없으면 지정가`() {
        val cycle =
            MartingaleCycle.from(
                listOf(trade(Side.BUY, 1, "272000", filled = null), trade(Side.BUY, 2, "270500", filled = "270000")),
            )
        assertEquals(listOf("272000", "270000"), cycle.buys.map { price(it.price) })
    }

    @Test
    fun `평단보다 높게 팔았으면 익절 낮게 팔았으면 손절`() {
        val takeProfit = MartingaleCycle.from(listOf(trade(Side.BUY, 1, "272000"), trade(Side.SELL, 1, "273500")))
        val stopLoss =
            MartingaleCycle.from(
                listOf(trade(Side.BUY, 1, "272000"), trade(Side.BUY, 2, "270500"), trade(Side.SELL, 3, "258000")),
            )
        assertTrue(takeProfit.lastSell!!.takeProfit)
        assertFalse(stopLoss.lastSell!!.takeProfit)
        assertFalse(stopLoss.holding)
    }

    // ── 추가 매수 ──

    @Test
    fun `트리거 가격은 평단 -0,5퍼센트에 가장 가까운 호가`() {
        // 272,000 × 0.995 = 270,640 → 270,500
        assertEquals("270500", price(rule.addOnTriggerPrice(BigDecimal("272000"), krw("271000"))))
    }

    @Test
    fun `트리거 경계 - 닿으면 직전 수량의 2배 한 호가 위면 안 산다`() {
        val cycle = cycleOf(1 to "272000")
        assertEquals(2, rule.nextQuantity(cycle, krw("270500")))
        assertNull(rule.nextQuantity(cycle, krw("271000")))
    }

    @Test
    fun `기준은 직전 매수가가 아니라 평단`() {
        // 평단 = (272,000 + 2×270,500) / 3 ≈ 271,000 → × 0.995 = 269,645 → 269,500. 직전 매수가 기준이었다면 269,000.
        val cycle = cycleOf(1 to "272000", 2 to "270500")
        assertNull(rule.nextQuantity(cycle, krw("270000")))
        assertEquals(4, rule.nextQuantity(cycle, krw("269500")))
    }

    @Test
    fun `사이클 평단은 수량 가중 평균이고 보유 중이 아니면 null`() {
        assertEquals("271000", price(cycleOf(1 to "272000", 2 to "270500").averagePrice!!.setScale(0, java.math.RoundingMode.HALF_UP)))
        assertNull(MartingaleCycle(emptyList(), null).averagePrice)
    }

    @Test
    fun `5단계 뒤에는 더 사지 않는다`() {
        val five = cycleOf(1 to "272000", 2 to "270500", 4 to "269000", 8 to "267500", 16 to "266000")
        assertNull(rule.nextQuantity(five, krw("100000")))
        val four = cycleOf(1 to "272000", 2 to "270500", 4 to "269000", 8 to "267500")
        assertEquals(16, rule.nextQuantity(four, krw("266000")))
    }

    @Test
    fun `보유 중이 아니면 추가 매수 없음`() {
        assertNull(rule.nextQuantity(MartingaleCycle(emptyList(), null), krw("1000")))
    }

    // ── 5단계 손절 ──

    @Test
    fun `1~4단계엔 손절이 없다`() {
        val four = cycleOf(1 to "272000", 2 to "270500", 4 to "269000", 8 to "267500")
        assertNull(rule.stopLossPrice(four, krw("100000")))
        assertFalse(rule.shouldStopLoss(four, krw("100000")))
    }

    @Test
    fun `5단계 손절 경계 - 마지막 매수가 -3퍼센트 호가 이하`() {
        val five = cycleOf(1 to "272000", 2 to "270500", 4 to "269000", 8 to "267500", 16 to "266000")
        // 266,000 × 0.97 = 258,020 → 258,000
        assertEquals("258000", price(rule.stopLossPrice(five, krw("260000"))))
        assertTrue(rule.shouldStopLoss(five, krw("258000")))
        assertFalse(rule.shouldStopLoss(five, krw("258500")))
    }

    // ── 재진입 ──

    @Test
    fun `익절 뒤엔 매도가 -0,5퍼센트에서 재진입`() {
        val cycle = MartingaleCycle(emptyList(), CycleSell(BigDecimal("273500"), takeProfit = true))
        // 273,500 × 0.995 = 272,132.5 → 272,000
        assertEquals("272000", price(rule.reentryTriggerPrice(cycle, krw("273000"))))
        assertTrue(rule.shouldReenter(cycle, krw("272000")))
        assertFalse(rule.shouldReenter(cycle, krw("272500")))
    }

    @Test
    fun `손절 뒤엔 매도가 -1퍼센트에서 재진입`() {
        val cycle = MartingaleCycle(emptyList(), CycleSell(BigDecimal("258000"), takeProfit = false))
        // 258,000 × 0.99 = 255,420 → 255,500
        assertEquals("255500", price(rule.reentryTriggerPrice(cycle, krw("257000"))))
        assertTrue(rule.shouldReenter(cycle, krw("255500")))
        assertFalse(rule.shouldReenter(cycle, krw("256000")))
    }

    @Test
    fun `매도 기록이 없거나 보유 중이면 재진입 없음`() {
        assertFalse(rule.shouldReenter(MartingaleCycle(emptyList(), null), krw("1000")))
        val holding = MartingaleCycle(listOf(CycleBuy(1, BigDecimal("272000"))), CycleSell(BigDecimal("273500"), true))
        assertFalse(rule.shouldReenter(holding, krw("1000")))
    }
}

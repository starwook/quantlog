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

    private fun cycleOf(vararg buys: Pair<Int, String>) = MartingaleCycle(buys.map { CycleBuy(it.first, BigDecimal(it.second)) })

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
    fun `매도하면 사이클이 끝나 보유 중이 아니다`() {
        val sold =
            MartingaleCycle.from(
                listOf(trade(Side.BUY, 1, "272000"), trade(Side.BUY, 2, "270500"), trade(Side.SELL, 3, "258000")),
            )
        assertFalse(sold.holding)
    }

    @Test
    fun `잔고가 있으면 수량·평단은 잔고 값이고 단계는 최소 1이다`() {
        val cycle = MartingaleCycle(emptyList()).withAccount(3, BigDecimal("271000"))
        assertTrue(cycle.holding)
        assertEquals(3, cycle.quantity)
        assertEquals("271000", price(cycle.averagePrice))
        assertEquals(1, cycle.stage)
    }

    @Test
    fun `매매 기록에 매수가 남아도 잔고에 없으면 보유 중이 아니다`() {
        val cycle = cycleOf(1 to "272000").withAccount(null, null)
        assertFalse(cycle.holding)
        assertNull(cycle.averagePrice)
    }

    // ── 추가 매수 ──

    @Test
    fun `트리거 가격은 평단 -0,5퍼센트에 가장 가까운 호가`() {
        // 272,000 × 0.995 = 270,640 → 270,500
        assertEquals("270500", price(rule.addOnTriggerPrice(BigDecimal("272000"), krw("271000"))))
    }

    @Test
    fun `트리거 경계 - 닿으면 보유 수량만큼 더 한 호가 위면 안 산다`() {
        val cycle = cycleOf(1 to "272000")
        assertEquals(1, rule.nextQuantity(cycle, krw("270500")))
        assertNull(rule.nextQuantity(cycle, krw("271000")))
    }

    @Test
    fun `기준은 직전 매수가가 아니라 평단`() {
        // 평단 = (272,000 + 2×270,500) / 3 ≈ 271,000 → × 0.995 = 269,645 → 269,500. 직전 매수가 기준이었다면 269,000.
        val cycle = cycleOf(1 to "272000", 2 to "270500")
        assertNull(rule.nextQuantity(cycle, krw("270000")))
        assertEquals(3, rule.nextQuantity(cycle, krw("269500"))) // 보유 3주 → 3주 더 = 6주
    }

    @Test
    fun `사이클 평단은 수량 가중 평균이고 보유 중이 아니면 null`() {
        assertEquals("271000", price(cycleOf(1 to "272000", 2 to "270500").averagePrice!!.setScale(0, java.math.RoundingMode.HALF_UP)))
        assertNull(MartingaleCycle(emptyList()).averagePrice)
    }

    @Test
    fun `5단계 뒤에는 더 사지 않는다`() {
        val five = cycleOf(1 to "272000", 2 to "270500", 4 to "269000", 8 to "267500", 16 to "266000")
        assertNull(rule.nextQuantity(five, krw("100000")))
        val four = cycleOf(1 to "272000", 2 to "270500", 4 to "269000", 8 to "267500")
        assertEquals(15, rule.nextQuantity(four, krw("266000")))
    }

    @Test
    fun `보유 중이 아니면 추가 매수 없음`() {
        assertNull(rule.nextQuantity(MartingaleCycle(emptyList()), krw("1000")))
    }

    // ── 5단계 손절 ──

    @Test
    fun `1~4단계엔 손절이 없다`() {
        val four = cycleOf(1 to "272000", 2 to "270500", 4 to "269000", 8 to "267500")
        assertNull(rule.stopLossPrice(four, krw("100000")))
        assertFalse(rule.shouldStopLoss(four, krw("100000")))
    }

    @Test
    fun `5단계 손절 경계 - 평단 -3퍼센트 호가 이하`() {
        val five = cycleOf(1 to "272000", 2 to "270500", 4 to "269000", 8 to "267500", 16 to "266000")
        // 평단 8,285,000 / 31 ≈ 267,258 × 0.97 = 259,240 → 259,000 (마지막 매수가 기준이었다면 258,000)
        assertEquals("259000", price(rule.stopLossPrice(five, krw("260000"))))
        assertTrue(rule.shouldStopLoss(five, krw("259000")))
        assertFalse(rule.shouldStopLoss(five, krw("259500")))
    }
}

package com.quantlog.position

import com.quantlog.broker.Market
import com.quantlog.broker.Side
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.context.ApplicationEventPublisher
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AccountCashServiceTest {
    private var row: AccountCash? = null
    private val repository =
        Mockito.mock(AccountCashRepository::class.java).also { repo ->
            Mockito.`when`(repo.findByCurrency("KRW")).thenAnswer { row }
            Mockito.`when`(repo.save(Mockito.any(AccountCash::class.java))).thenAnswer {
                it.getArgument<AccountCash>(0).also { saved -> row = saved }
            }
        }
    private val openBuys = mutableListOf<Trade>()
    private val tradeRepository =
        Mockito.mock(TradeRepository::class.java).also { repo ->
            Mockito.`when`(
                repo.findAllByMarketAndSideAndCanceledFalseAndExecutedAtGreaterThanEqual(
                    Mockito.any(Market::class.java) ?: Market.KR,
                    Mockito.any(Side::class.java) ?: Side.BUY,
                    Mockito.any(Instant::class.java) ?: Instant.EPOCH,
                ),
            ).thenAnswer { openBuys.filter { !it.canceled } }
        }
    private val service = AccountCashService(repository, tradeRepository, Mockito.mock(ApplicationEventPublisher::class.java))
    private val t0 = Instant.now().minusSeconds(60)

    private fun fill(
        side: Side,
        quantity: Int,
        price: String,
    ) = FillAppliedEvent(Market.KR, "233740", side, "A1", quantity, BigDecimal(price), null, Instant.now(), 1)

    private fun buyOrder(
        quantity: Int,
        price: String,
        filled: Int? = null,
        openConfirmed: Boolean = false,
        executedAt: Instant = Instant.now(),
    ) = Trade(
        market = Market.KR,
        symbol = "233740",
        side = Side.BUY,
        quantity = quantity,
        orderPrice = BigDecimal(price),
        orderNo = "A${openBuys.size + 1}",
        message = "ok",
        initialFilledPrice = filled?.let { BigDecimal(price) },
        initialFilledQuantity = filled,
        initialOpenConfirmed = openConfirmed,
        executedAt = executedAt,
    ).also { openBuys += it }

    private fun orderable() = service.orderable(Market.KR)

    private fun assertOrderable(expected: String) = assertEquals(0, BigDecimal(expected).compareTo(orderable()), "주문가능 ${orderable()}")

    @Test
    fun `잔고를 한 번도 못 받았으면 주문가능금액을 모른다`() {
        assertNull(orderable())
    }

    @Test
    fun `매도 체결은 바로 주문가능금액이 되고 매수 체결은 그만큼 줄인다`() {
        service.sync(Market.KR, BigDecimal("1000000"), t0)

        service.onFillApplied(fill(Side.BUY, 100, "6945"))
        assertOrderable("305500")

        service.onFillApplied(fill(Side.SELL, 100, "6910"))
        assertOrderable("996500")
    }

    @Test
    fun `매수 주문을 내면 지정가만큼 줄고 취소하면 다시 들어온다`() {
        service.sync(Market.KR, BigDecimal("1000000"), t0)
        val order = buyOrder(100, "7000")
        assertOrderable("300000")

        order.cancel()
        assertOrderable("1000000")
    }

    @Test
    fun `부분체결 뒤 취소하면 안 사진 만큼만 돌아온다`() {
        service.sync(Market.KR, BigDecimal("1000000"), t0)
        val order = buyOrder(100, "7000")
        // 40주가 6990 에 체결: 현금 -279,600, 남은 60주 × 7000 = 420,000 이 묶여 있다
        service.onFillApplied(fill(Side.BUY, 40, "6990"))
        order.applyFill(BigDecimal("6990"), 40)
        assertOrderable("300400")

        order.cancel()
        assertOrderable("720400")
    }

    @Test
    fun `전량 체결된 매수 주문은 돈을 더 잡지 않는다`() {
        service.sync(Market.KR, BigDecimal("1000000"), t0)
        buyOrder(100, "7000", filled = 100)
        assertOrderable("1000000")
    }

    @Test
    fun `체결인지 미체결인지 모르는 채로 오래된 주문은 돈을 잡지 않는다 - 체결통보를 놓쳐 기록만 낡은 경우`() {
        service.sync(Market.KR, BigDecimal("1000000"), t0)
        buyOrder(100, "7000", executedAt = Instant.now().minusSeconds(60))
        assertOrderable("1000000")
    }

    @Test
    fun `증권사가 미체결로 확인한 주문은 오래돼도 돈을 잡는다`() {
        service.sync(Market.KR, BigDecimal("1000000"), t0)
        buyOrder(100, "7000", openConfirmed = true, executedAt = Instant.now().minusSeconds(600))
        assertOrderable("300000")
    }

    @Test
    fun `잔고가 오면 증권사 값으로 덮어쓴다`() {
        service.sync(Market.KR, BigDecimal("1000000"), t0)
        service.sync(Market.KR, BigDecimal("999900"), t0.plusSeconds(10))
        assertOrderable("999900")
    }

    @Test
    fun `체결을 반영한 뒤에 도착한 그 전 시점의 잔고로는 덮어쓰지 않는다`() {
        service.sync(Market.KR, BigDecimal("1000000"), t0)
        service.onFillApplied(fill(Side.SELL, 100, "6910"))

        service.sync(Market.KR, BigDecimal("1000000"), t0.plusSeconds(10))

        assertOrderable("1691000")
    }
}

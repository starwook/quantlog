package com.quantlog.trading

import com.quantlog.broker.Market
import com.quantlog.position.AccountHolding
import com.quantlog.position.AccountHoldingRepository
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal
import kotlin.test.assertEquals

class DbRealtimeSymbolSourceTest {
    @Test
    fun `해당 시장에서 수량이 있는 보유 종목만 돌려준다`() {
        val holdings = Mockito.mock(AccountHoldingRepository::class.java)
        Mockito.`when`(holdings.findAll()).thenReturn(
            listOf(
                AccountHolding(Market.KR, "000660", 3, BigDecimal.ONE, BigDecimal.ONE),
                AccountHolding(Market.KR, "999999", 0, BigDecimal.ONE, BigDecimal.ONE),
                AccountHolding(Market.AMEX, "SOXL", 1, BigDecimal.ONE, BigDecimal.ONE),
            ),
        )

        assertEquals(listOf("000660"), DbRealtimeSymbolSource(holdings).symbols(Market.KR))
    }
}

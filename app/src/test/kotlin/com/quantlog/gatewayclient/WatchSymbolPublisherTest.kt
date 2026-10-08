package com.quantlog.gatewayclient

import com.quantlog.broker.Market
import com.quantlog.watchlist.SymbolStrategy
import com.quantlog.watchlist.SymbolStrategyService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito

class WatchSymbolPublisherTest {
    private val rows = mutableListOf<WatchSymbolRow>()
    private val repository =
        Mockito.mock(WatchSymbolRowRepository::class.java).also { repo ->
            Mockito.`when`(repo.findAll()).thenAnswer { rows.toList() }
            Mockito.`when`(
                repo.save(Mockito.any(WatchSymbolRow::class.java)),
            ).thenAnswer { it.getArgument<WatchSymbolRow>(0).also(rows::add) }
            Mockito.doAnswer { rows.remove(it.getArgument<WatchSymbolRow>(0)) }.`when`(repo).delete(Mockito.any(WatchSymbolRow::class.java))
        }
    private val strategies = Mockito.mock(SymbolStrategyService::class.java)

    private fun strategy(
        symbol: String,
        etf: Boolean,
    ): SymbolStrategy =
        Mockito.mock(SymbolStrategy::class.java).also {
            Mockito.`when`(it.market).thenReturn(Market.KR)
            Mockito.`when`(it.symbol).thenReturn(symbol)
            Mockito.`when`(it.etf).thenReturn(etf)
        }

    @Test
    fun `감시 종목을 계약 테이블에 추가·갱신·삭제로 맞춘다`() {
        rows += WatchSymbolRow("KR", "OLD", false)
        rows += WatchSymbolRow("KR", "005930", false)
        val wanted = listOf(strategy("005930", true), strategy("069500", true))
        Mockito.`when`(strategies.all()).thenReturn(wanted)

        WatchSymbolPublisher(strategies, repository).publish()

        assertEquals(setOf("005930", "069500"), rows.map { it.symbol }.toSet())
        assertEquals(true, rows.single { it.symbol == "005930" }.etf)
    }
}

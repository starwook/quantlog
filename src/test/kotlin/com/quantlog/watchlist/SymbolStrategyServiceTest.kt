package com.quantlog.watchlist

import com.quantlog.strategy.MartingaleProperties
import com.quantlog.strategy.StrategyProperties
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SymbolStrategyServiceTest {
    private val repository = Mockito.mock(SymbolStrategyRepository::class.java)
    private val service = SymbolStrategyService(repository, StrategyProperties(), MartingaleProperties())

    @Test
    fun `없는 종목 행만 기본값으로 만들고 삼성전자만 매수와 마틴게일을 켠다`() {
        // 삼성전자는 이미 사용자가 값을 바꿔둔 행이 있다고 가정 → 건드리면 안 된다.
        Mockito.`when`(repository.findByMarketAndSymbol(WatchedSymbol.SAMSUNG.market, WatchedSymbol.SAMSUNG.symbol))
            .thenReturn(symbolStrategy(WatchedSymbol.SAMSUNG.market, WatchedSymbol.SAMSUNG.symbol, takeProfit = "7"))
        Mockito.`when`(repository.save(Mockito.any(SymbolStrategy::class.java))).thenAnswer { it.arguments[0] }

        service.seedMissing()

        val captor = ArgumentCaptor.forClass(SymbolStrategy::class.java)
        Mockito.verify(repository, Mockito.times(WatchedSymbol.entries.size - 1)).save(captor.capture())
        val saved = captor.allValues
        assertTrue(saved.none { it.symbol == WatchedSymbol.SAMSUNG.symbol })
        assertTrue(saved.none { it.autoTrade || it.martingale })
    }

    @Test
    fun `행이 하나도 없으면 삼성전자만 매수와 마틴게일이 켜지고 값은 application yml 기본값`() {
        Mockito.`when`(repository.save(Mockito.any(SymbolStrategy::class.java))).thenAnswer { it.arguments[0] }

        service.seedMissing()

        val captor = ArgumentCaptor.forClass(SymbolStrategy::class.java)
        Mockito.verify(repository, Mockito.times(WatchedSymbol.entries.size)).save(captor.capture())
        val samsung = captor.allValues.single { it.symbol == WatchedSymbol.SAMSUNG.symbol }
        assertTrue(samsung.autoTrade && samsung.martingale)
        assertEquals(1, captor.allValues.count { it.autoTrade })
        assertEquals(0, BigDecimal("0.5").compareTo(MartingaleProperties().dropPercent))
        assertEquals(0, MartingaleProperties().dropPercent.compareTo(samsung.martingaleDropPercent))
    }
}

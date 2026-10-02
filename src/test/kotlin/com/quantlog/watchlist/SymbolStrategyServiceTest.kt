package com.quantlog.watchlist

import com.quantlog.broker.Market
import com.quantlog.strategy.MartingaleProperties
import com.quantlog.strategy.StrategyProperties
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SymbolStrategyServiceTest {
    private val repository = Mockito.mock(SymbolStrategyRepository::class.java)
    private val service = SymbolStrategyService(repository, StrategyProperties(), MartingaleProperties())

    @Test
    fun `없는 종목 행만 기본값으로 만들고 코스닥150레버리지만 매수와 마틴게일을 켠다`() {
        // 삼성전자는 이미 사용자가 값을 바꿔둔 행이 있다고 가정 → 건드리면 안 된다.
        Mockito.`when`(repository.findByMarketAndSymbol(SeedSymbol.SAMSUNG.market, SeedSymbol.SAMSUNG.symbol))
            .thenReturn(symbolStrategy(SeedSymbol.SAMSUNG.market, SeedSymbol.SAMSUNG.symbol, takeProfit = "7"))
        Mockito.`when`(repository.save(Mockito.any(SymbolStrategy::class.java))).thenAnswer { it.arguments[0] }

        service.seedMissing()

        val captor = ArgumentCaptor.forClass(SymbolStrategy::class.java)
        Mockito.verify(repository, Mockito.times(SeedSymbol.entries.size - 1)).save(captor.capture())
        val saved = captor.allValues
        assertTrue(saved.none { it.symbol == SeedSymbol.SAMSUNG.symbol })
        assertEquals(
            listOf(SeedSymbol.KODEX_KOSDAQ150_LEVERAGE.symbol),
            saved.filter { it.martingale }.map { it.symbol },
        )
    }

    @Test
    fun `행이 하나도 없으면 코스닥150레버리지만 매수와 마틴게일이 켜지고 값은 application yml 기본값`() {
        Mockito.`when`(repository.save(Mockito.any(SymbolStrategy::class.java))).thenAnswer { it.arguments[0] }

        service.seedMissing()

        val captor = ArgumentCaptor.forClass(SymbolStrategy::class.java)
        Mockito.verify(repository, Mockito.times(SeedSymbol.entries.size)).save(captor.capture())
        val target = captor.allValues.single { it.symbol == SeedSymbol.KODEX_KOSDAQ150_LEVERAGE.symbol }
        assertTrue(target.martingale && target.supportBounceEntry)
        assertEquals(1, captor.allValues.count { it.martingale })
        assertEquals(0, BigDecimal("0.5").compareTo(MartingaleProperties().dropPercent))
        assertEquals(0, MartingaleProperties().dropPercent.compareTo(target.martingaleDropPercent))
    }

    private fun validForm() =
        SymbolStrategyForm().apply {
            takeProfitPercent = BigDecimal("1")
            martingale = true
            martingaleDropPercent = BigDecimal("2")
            martingaleMultiplier = 3
            martingaleMaxStages = 4
            martingaleFinalStageStopLossPercent = BigDecimal("5")
            periodicRebuyQuantity = 3
            periodicRebuyIntervalMinutes = 15
            supportBounceQuantity = 4
        }

    @Test
    fun `화면 입력값이 유효하면 기존 행에 반영되고 손절은 비우면 보류`() {
        val existing = symbolStrategy(SeedSymbol.SAMSUNG.market, SeedSymbol.SAMSUNG.symbol, takeProfit = "0.5")
        Mockito.`when`(repository.findByMarketAndSymbol(existing.market, existing.symbol)).thenReturn(existing)

        service.update(existing.market, existing.symbol, validForm())

        assertEquals(0, BigDecimal("1").compareTo(existing.takeProfitPercent))
        assertEquals(null, existing.stopLossPercent)
        assertEquals(3, existing.martingaleMultiplier)
        assertEquals(4, existing.martingaleMaxStages)
        assertEquals(3, existing.periodicRebuyQuantity)
        assertEquals(15, existing.periodicRebuyIntervalMinutes)
        assertEquals(4, existing.supportBounceQuantity)
    }

    @Test
    fun `익절이 비었거나 배수가 2 미만이면 저장을 거부한다`() {
        val existing = symbolStrategy(SeedSymbol.SAMSUNG.market, SeedSymbol.SAMSUNG.symbol)
        Mockito.`when`(repository.findByMarketAndSymbol(existing.market, existing.symbol)).thenReturn(existing)

        assertFailsWith<IllegalArgumentException> {
            service.update(existing.market, existing.symbol, validForm().apply { takeProfitPercent = null })
        }
        assertFailsWith<IllegalArgumentException> {
            service.update(existing.market, existing.symbol, validForm().apply { martingaleMultiplier = 1 })
        }
        assertFailsWith<IllegalArgumentException> {
            service.update(existing.market, existing.symbol, validForm().apply { periodicRebuyQuantity = 0 })
        }
        assertFailsWith<IllegalArgumentException> {
            service.update(existing.market, existing.symbol, validForm().apply { periodicRebuyIntervalMinutes = 0 })
        }
        assertFailsWith<IllegalArgumentException> {
            service.update(existing.market, existing.symbol, validForm().apply { supportBounceQuantity = 0 })
        }
    }

    @Test
    fun `종목을 추가하면 코드는 대문자로, 매수 옵션은 모두 꺼진 기본값으로 저장한다`() {
        Mockito.`when`(repository.save(Mockito.any(SymbolStrategy::class.java))).thenAnswer { it.arguments[0] }

        service.add(Market.NASDAQ, " tsla ", " 테슬라 ")

        val captor = ArgumentCaptor.forClass(SymbolStrategy::class.java)
        Mockito.verify(repository).save(captor.capture())
        val saved = captor.value
        assertEquals("TSLA", saved.symbol)
        assertEquals("테슬라", saved.displayName)
        assertTrue(!saved.martingale && !saved.supportBounceEntry && !saved.periodicRebuy)
    }

    @Test
    fun `이미 있거나 비어 있는 종목은 추가할 수 없다`() {
        Mockito.`when`(repository.findByMarketAndSymbol(Market.KR, "005930")).thenReturn(symbolStrategy(Market.KR, "005930"))

        assertFailsWith<IllegalArgumentException> { service.add(Market.KR, "005930", "삼성전자") }
        assertFailsWith<IllegalArgumentException> { service.add(Market.KR, " ", "이름") }
        assertFailsWith<IllegalArgumentException> { service.add(Market.KR, "000660", " ") }
        Mockito.verify(repository, Mockito.never()).save(Mockito.any(SymbolStrategy::class.java))
    }
}

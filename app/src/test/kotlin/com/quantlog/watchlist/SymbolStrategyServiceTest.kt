package com.quantlog.watchlist

import com.quantlog.broker.Market
import com.quantlog.gatewayclient.WatchSymbolRow
import com.quantlog.gatewayclient.WatchSymbolRowRepository
import com.quantlog.strategy.MartingaleProperties
import com.quantlog.strategy.StrategyProperties
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import org.springframework.context.ApplicationEventPublisher
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SymbolStrategyServiceTest {
    private val repository = Mockito.mock(SymbolStrategyRepository::class.java)
    private val watchSymbols =
        Mockito.mock(WatchSymbolRowRepository::class.java).also { repo ->
            Mockito.`when`(repo.save(Mockito.any(WatchSymbolRow::class.java))).thenAnswer { it.arguments[0] }
        }
    private val events = Mockito.mock(ApplicationEventPublisher::class.java)
    private val service = SymbolStrategyService(repository, watchSymbols, StrategyProperties(), MartingaleProperties(), events)

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
    fun `마틴게일을 켠 종목의 손절은 추가매수 하락 퍼센트 이상이어야 한다`() {
        val existing = symbolStrategy(SeedSymbol.SAMSUNG.market, SeedSymbol.SAMSUNG.symbol, takeProfit = "0.5")
        Mockito.`when`(repository.findByMarketAndSymbol(existing.market, existing.symbol)).thenReturn(existing)

        // 하락 2% 인데 손절 1% → 첫 추가매수 전에 손절되므로 거절
        assertFailsWith<IllegalArgumentException> {
            service.update(existing.market, existing.symbol, validForm().apply { stopLossPercent = BigDecimal("1") })
        }
        // 같거나 크면 통과, 손절을 비우거나 마틴게일이 꺼져 있으면 검사 안 함
        service.update(existing.market, existing.symbol, validForm().apply { stopLossPercent = BigDecimal("2") })
        service.update(
            existing.market,
            existing.symbol,
            validForm().apply {
                stopLossPercent = BigDecimal("1")
                martingale = false
            },
        )
        assertEquals(0, BigDecimal("1").compareTo(existing.stopLossPercent))
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

        service.add(Market.KR, " 0000d0 ", " 테스트 ")

        val captor = ArgumentCaptor.forClass(SymbolStrategy::class.java)
        Mockito.verify(repository).save(captor.capture())
        val saved = captor.value
        assertEquals("0000D0", saved.symbol)
        assertEquals("테스트", saved.displayName)
        assertTrue(!saved.martingale && !saved.supportBounceEntry && !saved.periodicRebuy)
        // 종목 원본(watch_symbol)이 함께 만들어져 연결되고, 게이트웨이가 따라가도록 변경 신호가 나간다.
        assertEquals("0000D0", saved.watchSymbol?.symbol)
        Mockito.verify(watchSymbols).save(saved.watchSymbol!!)
        Mockito.verify(events).publishEvent(WatchSymbolsChanged)
    }

    @Test
    fun `종목을 삭제하면 설정과 watch_symbol 을 함께 지우고 변경 신호를 보낸다`() {
        val existing = symbolStrategy(Market.KR, "005930").also { it.watchSymbol = WatchSymbolRow("KR", "005930", false) }
        Mockito.`when`(repository.findByMarketAndSymbol(Market.KR, "005930")).thenReturn(existing)

        service.remove(Market.KR, "005930")

        Mockito.verify(repository).delete(existing)
        Mockito.verify(watchSymbols).delete(existing.watchSymbol!!)
        Mockito.verify(events).publishEvent(WatchSymbolsChanged)
        assertFailsWith<IllegalArgumentException> { service.remove(Market.KR, "000000") }
    }

    @Test
    fun `연결 없는 기존 행은 같은 종목의 watch_symbol 을 쓰고 없으면 옛 ETF 값으로 만든다`() {
        val withRow = symbolStrategy(Market.KR, "005930")
        val withoutRow = symbolStrategy(Market.KR, "091160").also { it.legacyEtf = true }
        val old = WatchSymbolRow("KR", "005930", false)
        Mockito.`when`(repository.findAll()).thenReturn(listOf(withRow, withoutRow))
        Mockito.`when`(watchSymbols.findByMarketAndSymbol("KR", "005930")).thenReturn(old)

        service.linkWatchSymbols()

        assertEquals(old, withRow.watchSymbol)
        assertTrue(withoutRow.etf && withoutRow.watchSymbol?.symbol == "091160")
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

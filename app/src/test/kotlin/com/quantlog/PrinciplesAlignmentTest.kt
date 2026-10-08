package com.quantlog

import com.quantlog.strategy.EntryStrategyProperties
import com.quantlog.strategy.MartingaleProperties
import com.quantlog.strategy.StrategyProperties
import com.quantlog.trading.EntryProperties
import com.quantlog.trading.EntryScheduler
import com.quantlog.trading.ExitProperties
import com.quantlog.trading.RiskProperties
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.MutablePropertySources
import org.springframework.core.io.ClassPathResource
import java.math.BigDecimal
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * playbook/principles.md 의 수치가 실제 설정(application.yml)·코드 기본값과 같은지 못 박는다. 원칙집 숫자를 바꾸면 이 테스트도 같이 고친다.
 * 읽는 파일은 클래스패스의 application.yml 뿐이다(개발자 시크릿 파일 application-local.yml 은 건드리지 않는다).
 */
class PrinciplesAlignmentTest {
    private val binder: Binder =
        MutablePropertySources()
            .also { it.addFirst(YamlPropertySourceLoader().load("application", ClassPathResource("application.yml")).single()) }
            .let { Binder(ConfigurationPropertySources.from(it), PropertySourcesPlaceholdersResolver(it)) }

    private inline fun <reified T : Any> bound(prefix: String): T = binder.bind(prefix, T::class.java).get()

    private fun assertDecimal(
        expected: String,
        actual: BigDecimal?,
    ) = assertEquals(0, BigDecimal(expected).compareTo(actual), "기대 $expected, 실제 $actual")

    @Test
    fun `익절 기본 +0,5퍼센트, 전역 손절은 보류`() {
        val strategy = bound<StrategyProperties>("quantlog.strategy")
        assertDecimal("0.5", strategy.takeProfitPercent)
        assertNull(strategy.stopLossPercent)
    }

    @Test
    fun `마틴게일 새 종목 기본값은 하락 0,5퍼센트 2배 최대 10단계`() {
        val martingale = bound<MartingaleProperties>("quantlog.strategy.martingale")
        assertDecimal("0.5", martingale.dropPercent)
        assertEquals(2, martingale.multiplier)
        assertEquals(10, martingale.maxStages)
    }

    @Test
    fun `저점 판단 진입 기준은 분봉 15개 지지선 0,3퍼센트 20칸 저가 절반`() {
        val entry = bound<EntryStrategyProperties>("quantlog.strategy.entry")
        assertEquals(15, entry.minCandles)
        assertDecimal("0.3", entry.nearSupportPercent)
        assertEquals(20, entry.supportBucketCount)
        assertDecimal("0.5", entry.supportZoneFraction)
    }

    @Test
    fun `리스크 가드는 국내 1000만원`() {
        val risk = bound<RiskProperties>("quantlog.risk")
        assertDecimal("10000000", risk.marketAllocationKrw)
    }

    @Test
    fun `쿨다운 10분 체결 대기 10초`() {
        assertEquals(Duration.ofMinutes(10), bound<EntryProperties>("quantlog.entry").cooldown)
        assertEquals(Duration.ofSeconds(10), bound<EntryProperties>("quantlog.entry").fillTimeout)
        assertEquals(Duration.ofSeconds(10), bound<ExitProperties>("quantlog.exit").fillTimeout)
    }

    @Test
    fun `저점 판단 진입의 매수 지정가 폭은 +0,5퍼센트`() {
        assertDecimal("0.005", EntryScheduler.BUY_OFFSET)
    }
}

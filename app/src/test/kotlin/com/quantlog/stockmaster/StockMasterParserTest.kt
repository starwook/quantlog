package com.quantlog.stockmaster

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StockMasterParserTest {
    // 2026-10-07 KIS 에서 받은 실제 레코드를 그대로 잘라 둔 파일이다(CP949, 고정폭).
    private fun sample(name: String) = javaClass.getResourceAsStream("/stockmaster/$name")!!.readBytes()

    @Test
    fun `KOSPI 실제 레코드에서 코드·이름·증권그룹을 바이트 위치로 읽는다`() {
        val records = StockMasterParser.parse(sample("kospi_sample.mst"), MasterExchange.KOSPI)

        val samsung = records.first()
        assertEquals("005930", samsung.shortCode)
        assertEquals("KR7005930003", samsung.standardCode)
        assertEquals("삼성전자", samsung.name)
        assertEquals("ST", samsung.securityGroup)
        assertFalse(samsung.tradingSuspended)
        assertFalse(samsung.managedIssue)
        assertEquals(listOf("ST", "EF", "EN"), records.map { it.securityGroup })
    }

    @Test
    fun `KOSDAQ 실제 레코드도 읽는다`() {
        val records = StockMasterParser.parse(sample("kosdaq_sample.mst"), MasterExchange.KOSDAQ)

        assertEquals(listOf("247540", "900110"), records.map { it.shortCode })
        assertEquals("에코프로비엠", records.first().name)
        assertEquals(MasterExchange.KOSDAQ, records.first().exchange)
    }

    @Test
    fun `ETF 만 etf 이고 ETN·주권은 아니다`() {
        val byGroup = StockMasterParser.parse(sample("kospi_sample.mst"), MasterExchange.KOSPI).map { StockMaster.from(it) }

        assertEquals(listOf(false, true, false), byGroup.map { it.isEtf })
    }

    @Test
    fun `거래정지·관리종목 플래그는 필드 위치에서 읽는다`() {
        val layout = StockMasterParser.layout(MasterExchange.KOSPI)
        val line = StockMasterParser.NAME_END + layout.tailLength
        val bytes = sample("kospi_sample.mst").copyOfRange(0, line) // 삼성전자 한 줄
        bytes[StockMasterParser.NAME_END + layout.offset("suspended")] = 'Y'.code.toByte()
        bytes[StockMasterParser.NAME_END + layout.offset("managed")] = 'Y'.code.toByte()

        val record = StockMasterParser.parse(bytes, MasterExchange.KOSPI).single()

        assertTrue(record.tradingSuspended)
        assertTrue(record.managedIssue)
    }

    @Test
    fun `레코드 길이가 사양과 다르면 조용히 넘기지 않고 실패한다`() {
        assertFailsWith<IllegalArgumentException> {
            StockMasterParser.parse("005930   KR7005930003삼성전자\n".toByteArray(charset("MS949")), MasterExchange.KOSPI)
        }
    }
}

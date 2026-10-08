package com.quantlog.stockmaster

import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.data.domain.PageRequest
import kotlin.test.assertEquals

class StockMasterServiceTest {
    private val page = PageRequest.of(0, 20)
    private val repository = Mockito.mock(StockMasterRepository::class.java)

    private fun stock(
        code: String,
        name: String,
        group: String = "ST",
    ) = StockMaster(code, "KR7$code", name, MasterExchange.KOSPI, group, tradingSuspended = false, managedIssue = false)

    private fun service(source: StockMasterSource = StockMasterSource { error("다운로드하면 안 됨") }) = StockMasterService(repository, source)

    @Test
    fun `검색은 코드 일치를 먼저 보여주고 같은 종목은 한 번만 나온다`() {
        val samsung = stock("005930", "삼성전자")
        val samsungSds = stock("018260", "삼성에스디에스")
        Mockito.`when`(repository.searchByCodePrefix("005", page)).thenReturn(listOf(samsung))
        Mockito.`when`(repository.searchByNameContaining("005", page))
            .thenReturn(listOf(samsung, samsungSds))

        val result = service().search(" 005 ")

        assertEquals(listOf("005930", "018260"), result.map { it.code })
    }

    @Test
    fun `빈 검색어는 DB 를 조회하지 않는다`() {
        assertEquals(emptyList(), service().search("  "))
        Mockito.verifyNoInteractions(repository)
    }

    @Test
    fun `ETF 는 etf 로 표시해 내려준다`() {
        val etf = stock("091160", "KODEX 반도체", group = "EF")
        Mockito.`when`(repository.searchByCodePrefix("KODEX", page)).thenReturn(emptyList())
        Mockito.`when`(repository.searchByNameContaining("KODEX", page)).thenReturn(listOf(etf))

        assertEquals(true, service().search("KODEX").single().etf)
    }

    @Test
    fun `다운로드가 실패하거나 건수가 비정상이면 DB 를 건드리지 않는다`() {
        val kospi = javaClass.getResourceAsStream("/stockmaster/kospi_sample.mst")!!.readBytes()
        val source =
            StockMasterSource { exchange ->
                if (exchange == MasterExchange.KOSDAQ) error("네트워크 오류") else kospi
            }
        // 코스닥은 다운로드 실패, 코스피는 샘플 3건이라 최소 건수 보호에 걸린다 → 둘 다 저장·삭제가 없어야 한다(전부 지우는 사고 방지).
        service(source).sync()

        Mockito.verify(repository, Mockito.never()).saveAll(Mockito.anyList<StockMaster>())
        Mockito.verify(repository, Mockito.never()).deleteAllByIdInBatch(Mockito.anyIterable<String>())
    }
}

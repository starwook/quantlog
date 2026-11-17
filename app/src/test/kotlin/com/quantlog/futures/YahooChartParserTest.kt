package com.quantlog.futures

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class YahooChartParserTest {
    private val mapper = ObjectMapper()

    /** 2026-11-17 실측 응답에서 필요한 칸만 남긴 것. 1794787800 = 2026-11-16 09:10 KST. */
    private fun body(meta: String) =
        mapper.readTree(
            """
            {"chart":{"result":[{"meta":{$meta},
              "timestamp":[1794787800,1794787860,1794787920],
              "indicators":{"quote":[{
                "open":[27300.25,null,27310.0],"high":[27305.5,null,27320.0],
                "low":[27299.0,null,27309.5],"close":[27304.0,null,27319.0],"volume":[120,null,0]}]}}],"error":null}}
            """.trimIndent(),
        )

    @Test
    fun `1분봉은 한국 시간으로 바꾸고 빈 봉은 건너뛴다`() {
        val quote = YahooChartParser.parse(body("\"previousClose\":27284.25"), daily = false)

        assertThat(quote.previousClose).isEqualByComparingTo("27284.25")
        assertThat(quote.candles.map { it.date to it.time }).containsExactly("20261116" to "091000", "20261116" to "091200")
        assertThat(quote.candles.first().close).isEqualByComparingTo(BigDecimal("27304.0"))
        assertThat(quote.candles.first().volume).isEqualTo(120)
    }

    @Test
    fun `일봉은 시각이 없고 전일 종가가 없을 수 있다`() {
        val quote = YahooChartParser.parse(body("\"chartPreviousClose\":29391.5"), daily = true)

        assertThat(quote.previousClose).isNull()
        assertThat(quote.candles.map { it.time }).containsOnlyNulls()
    }

    @Test
    fun `야후가 오류를 주면 그 설명으로 실패한다`() {
        val error = mapper.readTree("""{"chart":{"result":null,"error":{"code":"Not Found","description":"No data found"}}}""")

        assertThatThrownBy { YahooChartParser.parse(error, daily = false) }.hasMessageContaining("No data found")
    }
}

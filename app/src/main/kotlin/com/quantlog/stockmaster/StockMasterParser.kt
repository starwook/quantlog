package com.quantlog.stockmaster

import java.nio.charset.Charset

/** 마스터 파일 한 줄에서 우리가 쓰는 값만 뽑은 것. */
data class StockMasterRecord(
    val shortCode: String,
    val standardCode: String,
    val name: String,
    val exchange: MasterExchange,
    val securityGroup: String,
    val tradingSuspended: Boolean,
    val managedIssue: Boolean,
)

/**
 * KIS 종목 마스터(`kospi_code.mst`, `kosdaq_code.mst`) 파서. 2026-10-07 실제 파일로 검증한 사양:
 * - 인코딩 CP949, 줄바꿈 `\n`, 한 줄이 고정폭 바이트 레코드(KOSPI 288, KOSDAQ 282바이트). 한글이 2바이트라 문자 index 가 아니라 **바이트 offset** 으로 자른다.
 * - 앞부분: 단축코드 0..9, 표준코드 9..21, 종목명 21..61(공백 패딩). 그 뒤가 [Layout] 의 고정폭 필드(KOSPI 70개 227바이트, KOSDAQ 64개 221바이트).
 * 상세 필드표는 docs/kis-api/stock-master.md.
 */
object StockMasterParser {
    private val CP949: Charset = Charset.forName("MS949")
    private const val SHORT_CODE_END = 9
    private const val STANDARD_CODE_END = 21
    const val NAME_END = 61

    /** 고정폭 뒷부분의 필드 목록. 이름으로 offset 을 찾는다. */
    class Layout(private val fields: List<Pair<String, Int>>) {
        val tailLength: Int = fields.sumOf { it.second }
        private val offsets: Map<String, Int> =
            fields.runningFold(0) { acc, f -> acc + f.second }
                .zip(fields) { offset, f -> f.first to offset }
                .toMap()
        private val widths: Map<String, Int> = fields.toMap()

        fun offset(field: String): Int = offsets.getValue(field)

        fun text(
            tail: ByteArray,
            field: String,
        ): String = String(tail, offset(field), widths.getValue(field), CP949).trim()
    }

    // 필드 순서·폭은 KIS 공식 정제 코드(open-trading-api/stocks_info)와 실제 파일 대조로 확정했다. 합이 레코드 뒷부분 길이와 같아야 한다.
    private val KOSPI_LAYOUT =
        Layout(
            listOf(
                "group" to 2, "capSize" to 1, "sectorL" to 4, "sectorM" to 4, "sectorS" to 4, "manufacturing" to 1,
                "lowLiquidity" to 1, "governance" to 1, "k200Sector" to 1, "k100" to 1, "k50" to 1, "krx" to 1, "etp" to 1,
                "elwIssued" to 1, "krx100" to 1, "krxAuto" to 1, "krxSemi" to 1, "krxBio" to 1, "krxBank" to 1, "spac" to 1,
                "krxEnergy" to 1, "krxSteel" to 1, "shortOverheat" to 1, "krxMedia" to 1, "krxConstruction" to 1, "non1" to 1,
                "krxSecurities" to 1, "krxShip" to 1, "krxInsurance" to 1, "krxTransport" to 1, "sri" to 1, "basePrice" to 9,
                "tradeUnit" to 5, "afterHoursUnit" to 5, "suspended" to 1, "liquidation" to 1, "managed" to 1, "marketWarning" to 2,
                "warningNotice" to 1, "unfaithful" to 1, "backdoor" to 1, "lockType" to 2, "parChange" to 2, "capitalIncrease" to 2,
                "margin" to 3, "creditAllowed" to 1, "creditDays" to 3, "prevVolume" to 12, "parValue" to 12, "listedDate" to 8,
                "listedShares" to 15, "capital" to 21, "settlementMonth" to 2, "ipoPrice" to 7, "preferred" to 1,
                "shortSellOverheat" to 1, "surge" to 1, "krx300" to 1, "kospi" to 1, "sales" to 9, "operatingProfit" to 9,
                "ordinaryProfit" to 9, "netIncome" to 5, "roe" to 9, "baseYearMonth" to 8, "marketCap" to 9, "groupCode" to 3,
                "creditLimitExceeded" to 1, "collateralLoan" to 1, "lending" to 1,
            ),
        )
    private val KOSDAQ_LAYOUT =
        Layout(
            listOf(
                "group" to 2, "capSize" to 1, "sectorL" to 4, "sectorM" to 4, "sectorS" to 4, "venture" to 1,
                "lowLiquidity" to 1, "krx" to 1, "etp" to 1, "krx100" to 1, "krxAuto" to 1, "krxSemi" to 1, "krxBio" to 1,
                "krxBank" to 1, "spac" to 1, "krxEnergy" to 1, "krxSteel" to 1, "shortOverheat" to 1, "krxMedia" to 1,
                "krxConstruction" to 1, "attentionIssue" to 1, "krxSecurities" to 1, "krxShip" to 1, "krxInsurance" to 1,
                "krxTransport" to 1, "kosdaq150" to 1, "basePrice" to 9, "tradeUnit" to 5, "afterHoursUnit" to 5,
                "suspended" to 1, "liquidation" to 1, "managed" to 1, "marketWarning" to 2, "warningNotice" to 1,
                "unfaithful" to 1, "backdoor" to 1, "lockType" to 2, "parChange" to 2, "capitalIncrease" to 2, "margin" to 3,
                "creditAllowed" to 1, "creditDays" to 3, "prevVolume" to 12, "parValue" to 12, "listedDate" to 8,
                "listedShares" to 15, "capital" to 21, "settlementMonth" to 2, "ipoPrice" to 7, "preferred" to 1,
                "shortSellOverheat" to 1, "surge" to 1, "krx300" to 1, "sales" to 9, "operatingProfit" to 9,
                "ordinaryProfit" to 9, "netIncome" to 5, "roe" to 9, "baseYearMonth" to 8, "marketCap" to 9, "groupCode" to 3,
                "creditLimitExceeded" to 1, "collateralLoan" to 1, "lending" to 1,
            ),
        )

    fun layout(exchange: MasterExchange) =
        when (exchange) {
            MasterExchange.KOSPI -> KOSPI_LAYOUT
            MasterExchange.KOSDAQ -> KOSDAQ_LAYOUT
        }

    /** 레코드 형식에 안 맞는 줄(길이 불일치 등)은 건너뛰지 않고 예외를 던진다 — 사양이 바뀐 걸 조용히 놓치지 않으려는 것. */
    fun parse(
        content: ByteArray,
        exchange: MasterExchange,
    ): List<StockMasterRecord> {
        val layout = layout(exchange)
        val recordLength = NAME_END + layout.tailLength
        val records = mutableListOf<StockMasterRecord>()
        var start = 0
        while (start < content.size) {
            var end = start
            while (end < content.size && content[end] != '\n'.code.toByte()) end++
            val line = content.copyOfRange(start, end)
            start = end + 1
            if (line.isEmpty()) continue
            require(line.size == recordLength) { "$exchange 마스터 레코드 길이가 ${line.size} 바이트입니다(기대 $recordLength) — 파일 사양이 바뀌었는지 확인하세요" }
            val tail = line.copyOfRange(NAME_END, line.size)
            records +=
                StockMasterRecord(
                    shortCode = String(line, 0, SHORT_CODE_END, CP949).trim(),
                    standardCode = String(line, SHORT_CODE_END, STANDARD_CODE_END - SHORT_CODE_END, CP949).trim(),
                    name = String(line, STANDARD_CODE_END, NAME_END - STANDARD_CODE_END, CP949).trim(),
                    exchange = exchange,
                    securityGroup = layout.text(tail, "group"),
                    tradingSuspended = layout.text(tail, "suspended") == "Y",
                    managedIssue = layout.text(tail, "managed") == "Y",
                )
        }
        return records
    }
}

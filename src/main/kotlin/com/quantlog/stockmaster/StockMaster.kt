package com.quantlog.stockmaster

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/** KIS 가 마스터 파일을 나눠 배포하는 단위. [fileName] 은 [StockMasterSource] 의 다운로드 주소 아래에 있다. */
enum class MasterExchange(val fileName: String) {
    KOSPI("kospi_code.mst.zip"),
    KOSDAQ("kosdaq_code.mst.zip"),
}

/**
 * KIS 종목 마스터(국내 주식·ETF 등 전체 종목) 한 줄. 종목 추가 화면의 검색 대상이다.
 * 매일 [StockMasterService.sync] 가 마스터 파일로 덮어쓰므로 사용자가 고치는 값은 없다 — 매매 설정은 `symbol_strategy` 쪽이다.
 */
@Entity
@Table(name = "stock_master")
class StockMaster(
    /** 단축코드(005930). 숫자가 아니라 문자열이다(앞자리 0, 영문 섞인 코드). */
    @Id
    @Column(name = "short_code", length = 9)
    val shortCode: String,
    @Column(name = "standard_code", nullable = false, length = 12)
    var standardCode: String,
    @Column(nullable = false, length = 60)
    var name: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    var exchange: MasterExchange,
    /** 증권그룹구분코드: ST 주권, EF ETF, EN ETN, RT 리츠, BC 수익증권 등. */
    @Column(name = "security_group", nullable = false, length = 2)
    var securityGroup: String,
    @Column(name = "trading_suspended", nullable = false)
    var tradingSuspended: Boolean,
    @Column(name = "managed_issue", nullable = false)
    var managedIssue: Boolean,
) {
    /** 국내 ETF 는 증권거래세가 없어 모킹 체결 계산이 달라진다 — 종목 설정의 etf 플래그 기본값으로 쓴다. ETN 은 ETF 가 아니다. */
    val isEtf: Boolean get() = securityGroup == ETF_GROUP

    fun update(record: StockMasterRecord) {
        standardCode = record.standardCode
        name = record.name
        exchange = record.exchange
        securityGroup = record.securityGroup
        tradingSuspended = record.tradingSuspended
        managedIssue = record.managedIssue
    }

    companion object {
        const val ETF_GROUP = "EF"

        fun from(record: StockMasterRecord) =
            StockMaster(
                shortCode = record.shortCode,
                standardCode = record.standardCode,
                name = record.name,
                exchange = record.exchange,
                securityGroup = record.securityGroup,
                tradingSuspended = record.tradingSuspended,
                managedIssue = record.managedIssue,
            )
    }
}

interface StockMasterRepository : JpaRepository<StockMaster, String> {
    @Query("select s from StockMaster s where s.shortCode like concat(:q, '%') order by s.shortCode")
    fun searchByCodePrefix(
        @Param("q") q: String,
        page: Pageable,
    ): List<StockMaster>

    @Query("select s from StockMaster s where s.name like concat('%', :q, '%') order by s.name")
    fun searchByNameContaining(
        @Param("q") q: String,
        page: Pageable,
    ): List<StockMaster>

    fun findAllByExchange(exchange: MasterExchange): List<StockMaster>
}

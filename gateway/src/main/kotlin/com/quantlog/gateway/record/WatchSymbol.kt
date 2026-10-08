package com.quantlog.gateway.record

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.springframework.data.jpa.repository.JpaRepository

/**
 * 앱이 써 주는 "구독·수집할 종목" 계약 테이블(docs/contracts/watch_symbol.md). **컬럼은 일부러 최소**다 — 앱의 `symbol_strategy` 는 전략 옵션이
 * 자주 늘어나서 게이트웨이가 그걸 직접 읽으면 앱 기능 추가가 게이트웨이 재배포로 번진다. 게이트웨이는 이 테이블만 읽고 쓰지 않는다.
 */
@Entity
@Table(name = "watch_symbol", uniqueConstraints = [UniqueConstraint(columnNames = ["market", "symbol"])])
class WatchSymbol(
    @Column(nullable = false, length = 20)
    val market: String,
    @Column(nullable = false, length = 20)
    val symbol: String,
    /** ETF 면 true (모킹 체결의 제세금 계산용). */
    @Column(nullable = false)
    val etf: Boolean = false,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set
}

interface WatchSymbolRepository : JpaRepository<WatchSymbol, Long> {
    fun findByMarketAndSymbol(
        market: String,
        symbol: String,
    ): WatchSymbol?
}

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
 * 앱이 써 주는 "지금 보유 중인 종목" 계약 테이블(docs/contracts/README.md). 보유 종목은 실시간 구독·분봉 수집 대상에 먼저 넣는데,
 * 잔고 원문([KisBalance])은 게이트웨이가 해석하지 않으므로 어느 종목을 들고 있는지는 앱이 알려 준다. 게이트웨이는 읽기만 한다.
 */
@Entity
@Table(name = "held_symbol", uniqueConstraints = [UniqueConstraint(columnNames = ["market", "symbol"])])
class HeldSymbol(
    @Column(nullable = false, length = 20)
    val market: String,
    @Column(nullable = false, length = 20)
    val symbol: String,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set
}

interface HeldSymbolRepository : JpaRepository<HeldSymbol, Long>

package com.quantlog.position

import com.quantlog.broker.Market
import org.springframework.data.jpa.repository.JpaRepository

interface AccountHoldingRepository : JpaRepository<AccountHolding, Long> {
    fun findByMarketAndSymbol(
        market: Market,
        symbol: String,
    ): AccountHolding?
}

package com.quantlog.position

import org.springframework.data.jpa.repository.JpaRepository

interface TradeRepository : JpaRepository<Trade, Long> {
    fun findAllByOrderByExecutedAtDesc(): List<Trade>
}

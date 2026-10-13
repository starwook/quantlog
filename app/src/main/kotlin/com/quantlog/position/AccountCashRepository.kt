package com.quantlog.position

import org.springframework.data.jpa.repository.JpaRepository

interface AccountCashRepository : JpaRepository<AccountCash, Long> {
    fun findByCurrency(currency: String): AccountCash?
}

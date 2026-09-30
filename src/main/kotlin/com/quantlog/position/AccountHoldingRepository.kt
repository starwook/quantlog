package com.quantlog.position

import org.springframework.data.jpa.repository.JpaRepository

interface AccountHoldingRepository : JpaRepository<AccountHolding, Long>

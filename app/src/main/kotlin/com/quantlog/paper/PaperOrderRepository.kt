package com.quantlog.paper

import com.quantlog.broker.Market
import org.springframework.data.jpa.repository.JpaRepository

interface PaperOrderRepository : JpaRepository<PaperOrder, Long> {
    fun findAllByMarketInOrderByIdAsc(markets: Collection<Market>): List<PaperOrder>
}

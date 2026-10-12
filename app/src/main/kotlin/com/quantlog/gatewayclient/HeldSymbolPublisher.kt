package com.quantlog.gatewayclient

import com.quantlog.broker.Holding
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/** 보유 종목을 게이트웨이에 알리는 계약 테이블(`held_symbol`)을 지금 보유 종목과 같게 맞춘다. */
@Component
class HeldSymbolPublisher(
    private val rows: HeldSymbolRowRepository,
) {
    @Transactional
    fun publish(holdings: List<Holding>) {
        val wanted = holdings.map { it.market.name to it.symbol }.toSet()
        val existing = rows.findAll().associateBy { it.market to it.symbol }
        existing.filterKeys { it !in wanted }.values.forEach { rows.delete(it) }
        (wanted - existing.keys).forEach { (market, symbol) -> rows.save(HeldSymbolRow(market, symbol)) }
    }
}

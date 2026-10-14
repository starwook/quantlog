package com.quantlog.position

import java.lang.reflect.Proxy

/**
 * 테스트용 메모리 `order_fill`. [repository] 는 [HoldingFillApplier] 가 쓰는 메서드만 흉내 낸다.
 * [InMemoryBrokerFills] 처럼 **날짜는 보지 않는다** — 테스트의 통보 시각(EPOCH 등)과 매매 기록 시각(지금)이 날짜가 달라도 같은 주문으로 본다.
 */
class InMemoryOrderFillStates {
    val rows = mutableListOf<OrderFillState>()

    val repository: OrderFillStateRepository =
        Proxy.newProxyInstance(
            OrderFillStateRepository::class.java.classLoader,
            arrayOf(OrderFillStateRepository::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "findByTradeDateAndMarketAndOrderNo" -> rows.firstOrNull { it.market == args[1] && it.orderNo == args[2] }
                "findAllByTradeDate" -> rows.toList()
                "existsByTradeDate" -> rows.isNotEmpty()
                "findAll" -> rows.toList()
                "save" -> args[0].also { if (rows.none { r -> r === it }) rows += it as OrderFillState }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args[0]
                "toString" -> "InMemoryOrderFillStates"
                else -> throw UnsupportedOperationException(method.name)
            }
        } as OrderFillStateRepository

    val states = OrderFillStates(repository)

    fun of(orderNo: String): OrderFillState? = rows.firstOrNull { it.orderNo == orderNo }
}

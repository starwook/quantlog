package com.quantlog.watchlist

import org.mockito.Mockito

/** 주어진 설정만 조회되는 SymbolStrategyService. 목록에 없는 종목은 null(행 없음). */
fun symbolStrategyServiceOf(vararg configs: SymbolStrategy): SymbolStrategyService {
    val service = Mockito.mock(SymbolStrategyService::class.java)
    configs.forEach { Mockito.`when`(service.find(it.market, it.symbol)).thenReturn(it) }
    Mockito.`when`(service.all()).thenReturn(configs.toList())
    return service
}

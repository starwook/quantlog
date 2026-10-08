package com.quantlog.position

import org.springframework.context.ApplicationEventPublisher
import java.lang.reflect.Proxy

/** 테스트용 메모리 체결 원장. 실제 저장소처럼 저장한 줄을 주문번호로 다시 읽을 수 있다. */
class InMemoryTradeFills {
    val rows = mutableListOf<TradeFill>()

    val repository: TradeFillRepository =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(TradeFillRepository::class.java)) { _, method, args ->
            when (method.name) {
                "save" -> args[0].also { rows += it as TradeFill }
                "findAllByMarketAndOrderNo" -> rows.filter { it.market == args[0] && it.orderNo == args[1] }
                else -> throw UnsupportedOperationException(method.name)
            }
        } as TradeFillRepository

    /** [FillAppliedEvent] 는 실제처럼 원장에 적고, 나머지 이벤트는 [sink] 로 모은다. */
    fun publisher(sink: MutableList<Any> = mutableListOf()) =
        ApplicationEventPublisher { event ->
            if (event is FillAppliedEvent) FillLedger(repository).record(event)
            sink += event
        }
}

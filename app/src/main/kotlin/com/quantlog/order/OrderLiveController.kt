package com.quantlog.order

import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

/** 주문이 체결·미체결되는 모습을 실시간으로 보는 화면. 데이터는 전부 웹소켓(/ws/portfolio)으로 받는다. */
@Controller
class OrderLiveController {
    @GetMapping("/orders")
    fun orders(): String = "orders"
}

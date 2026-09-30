package com.quantlog.trading

import com.quantlog.broker.Market
import com.quantlog.watchlist.SymbolStrategyService
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.servlet.mvc.support.RedirectAttributes

/** 계좌 화면(/)의 보유 종목 "전량 매도" 버튼이 부르는 곳. */
@Controller
class ManualSellController(
    private val service: ManualSellService,
    private val symbolStrategyService: SymbolStrategyService,
) {
    @PostMapping("/holdings/{market}/{symbol}/sell")
    fun sell(
        @PathVariable market: Market,
        @PathVariable symbol: String,
        redirect: RedirectAttributes,
    ): String {
        val name = symbolStrategyService.displayName(market, symbol)
        runCatching { service.sellAll(market, symbol) }
            .onSuccess { redirect.addFlashAttribute("message", "$name ${it}주 전량 매도 주문을 냈어요.") }
            .onFailure { redirect.addFlashAttribute("error", "$name 매도 실패: ${it.message}") }
        return "redirect:/"
    }
}

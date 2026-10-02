package com.quantlog.trading

import com.quantlog.broker.Market
import com.quantlog.broker.Side
import com.quantlog.watchlist.SymbolStrategyService
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes

/** 관심종목 화면(/chart/{market}/{symbol})의 "매수"·"매도" 버튼이 부르는 곳. */
@Controller
class ManualOrderController(
    private val service: ManualOrderService,
    private val symbolStrategyService: SymbolStrategyService,
) {
    @PostMapping("/chart/{market}/{symbol}/order")
    fun order(
        @PathVariable market: Market,
        @PathVariable symbol: String,
        @RequestParam side: Side,
        @RequestParam quantity: Int,
        redirect: RedirectAttributes,
    ): String {
        val name = symbolStrategyService.displayName(market, symbol)
        val label = if (side == Side.BUY) "매수" else "매도"
        runCatching {
            if (side == Side.BUY) service.buy(market, symbol, quantity) else service.sell(market, symbol, quantity)
        }
            .onSuccess { redirect.addFlashAttribute("message", "$name ${quantity}주 $label 주문을 냈어요.") }
            .onFailure { redirect.addFlashAttribute("error", "$name $label 실패: ${it.message}") }
        return "redirect:/chart/$market/$symbol"
    }
}

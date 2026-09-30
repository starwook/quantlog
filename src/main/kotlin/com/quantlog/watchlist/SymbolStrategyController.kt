package com.quantlog.watchlist

import com.quantlog.broker.Market
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.servlet.mvc.support.RedirectAttributes

/** 종목별 매매 설정 화면. 값을 저장하면 스케줄러가 다음 주기부터 그대로 쓴다(재시작 불필요). */
@Controller
class SymbolStrategyController(
    private val service: SymbolStrategyService,
) {
    @GetMapping("/settings")
    fun settings(model: Model): String {
        model.addAttribute("rows", service.all())
        return "settings"
    }

    @PostMapping("/settings/{market}/{symbol}")
    fun save(
        @PathVariable market: Market,
        @PathVariable symbol: String,
        @ModelAttribute form: SymbolStrategyForm,
        redirect: RedirectAttributes,
    ): String {
        val name = service.displayName(market, symbol)
        runCatching { service.update(market, symbol, form) }
            .onSuccess { redirect.addFlashAttribute("message", "$name 설정을 저장했어요. 다음 주기(1초 이내)부터 적용됩니다.") }
            .onFailure { redirect.addFlashAttribute("error", "$name 저장 실패: ${it.message}") }
        return "redirect:/settings"
    }
}

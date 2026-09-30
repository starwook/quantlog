package com.quantlog.trading

import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.servlet.mvc.support.RedirectAttributes

/** 계좌 화면(/)의 "KIS 잔고와 맞추기" 버튼이 부르는 곳. */
@Controller
class HoldingSyncController(
    private val service: HoldingSyncService,
) {
    @PostMapping("/holdings/sync")
    fun sync(redirect: RedirectAttributes): String {
        runCatching { service.sync() }
            .onSuccess {
                redirect.addFlashAttribute("message", if (it.isEmpty()) "이미 KIS 잔고와 같아요." else "KIS 잔고에 맞췄어요: ${it.joinToString(" / ")}")
            }
            .onFailure { redirect.addFlashAttribute("error", "잔고 동기화 실패: ${it.message}") }
        return "redirect:/"
    }
}

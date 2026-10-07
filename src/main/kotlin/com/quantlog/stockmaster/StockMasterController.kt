package com.quantlog.stockmaster

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** 종목 추가 화면의 자동완성이 부르는 검색. */
@RestController
class StockMasterController(
    private val service: StockMasterService,
) {
    @GetMapping("/stocks/search")
    fun search(
        @RequestParam q: String,
    ): List<StockSearchResult> = service.search(q)
}

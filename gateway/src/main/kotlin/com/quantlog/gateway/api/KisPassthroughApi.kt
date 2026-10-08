package com.quantlog.gateway.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.quantlog.gateway.broker.CallPriority
import com.quantlog.gateway.kis.KisApiClient
import com.quantlog.gateway.kis.KisProperties
import com.quantlog.gateway.record.KisOrderService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RestController

/**
 * 앱 → 한투 REST 통로. `/api/kis/<한투 경로>` 로 온 요청을 한투에 그대로 전하고 **한투 응답(상태·본문·`tr_cont`)을 그대로** 돌려준다 —
 * 응답을 읽거나 바꾸지 않으므로 한투 응답 형식이 바뀌어도 앱만 고치면 된다. 해석은 앱의 `KisBrokerClient` 가 한투 공식 문서대로 한다.
 *
 * 게이트웨이가 하는 일: 토큰·호출 간격·우선순위(`X-Call-Priority: urgent`)·한도 초과 재시도([KisApiClient]),
 * 계좌가 필요한 TR 에 `CANO`·`ACNT_PRDT_CD` 끼워 넣기([KisAccountTr]) — 계좌번호는 시크릿이라 앱이 모른다.
 * 요청 헤더 `tr_id` 는 필수, `tr_cont` 는 연속조회용(그대로 전달). 주문·취소처럼 두 번 나가면 안 되는 요청은 헤더 `X-Request-Id`(앱이 요청마다 새로 만든 ID)를
 * 붙인다 — 그러면 [KisOrderService] 가 요청·응답 원문을 `kis_order` 에 남기고 같은 ID 의 재요청은 저장된 응답을 돌려준다. 형식은 docs/contracts/README.md.
 */
@RestController
@RequestMapping("/api/kis")
class KisPassthroughApi(
    private val api: KisApiClient,
    private val properties: KisProperties,
    private val orders: KisOrderService,
    private val objectMapper: ObjectMapper,
) {
    @RequestMapping("/**", method = [RequestMethod.GET, RequestMethod.POST])
    fun forward(
        request: HttpServletRequest,
        @RequestBody(required = false) body: String?,
        @RequestHeader(name = "tr_id", required = false) trId: String?,
        @RequestHeader(name = "tr_cont", required = false) trCont: String?,
        @RequestHeader(name = PRIORITY_HEADER, required = false) priority: String?,
        @RequestHeader(name = REQUEST_ID_HEADER, required = false) requestId: String?,
    ): ResponseEntity<String> {
        val path = request.requestURI.removePrefix("/api/kis")
        require(path.startsWith("/uapi/") && ".." !in path) { "한투 경로(/uapi/...)만 전달한다: $path" }
        require(!trId.isNullOrBlank()) { "tr_id 헤더가 필요하다" }
        val post = request.method == "POST"
        val account = KisAccountTr.matches(trId)
        val query =
            if (post) {
                emptyMap()
            } else {
                request.parameterMap.mapValues { it.value.firstOrNull().orEmpty() } +
                    if (account) accountParams() else emptyMap()
            }
        val jsonBody = if (post) withAccount(body, account) else null
        val send = { api.raw(path, trId, request.method, query, jsonBody, trCont) }
        val call = { if (requestId.isNullOrBlank()) send() else orders.forward(requestId, trId, path, body.orEmpty(), send) }
        val response = if (priority.equals(URGENT, ignoreCase = true)) CallPriority.urgent { call() } else call()
        val result = ResponseEntity.status(HttpStatus.valueOf(response.status)).contentType(MediaType.APPLICATION_JSON)
        response.trCont?.let { result.header("tr_cont", it) }
        return result.body(response.body)
    }

    private fun accountParams() = mapOf("CANO" to properties.accountNumber, "ACNT_PRDT_CD" to properties.accountProductCode)

    private fun withAccount(
        body: String?,
        account: Boolean,
    ): String {
        if (!account) return body.orEmpty()
        val node = (body?.takeIf { it.isNotBlank() }?.let { objectMapper.readTree(it) } ?: objectMapper.createObjectNode()) as ObjectNode
        accountParams().forEach { (k, v) -> node.put(k, v) }
        return objectMapper.writeValueAsString(node)
    }

    companion object {
        const val PRIORITY_HEADER = "X-Call-Priority"
        const val REQUEST_ID_HEADER = "X-Request-Id"
        const val URGENT = "urgent"
    }
}

/** 계좌번호(`CANO`·`ACNT_PRDT_CD`)가 필요한 한투 TR. 게이트웨이가 끼워 넣는다 — 모의투자(V)·실전(T) 모두. */
object KisAccountTr {
    private val IDS =
        setOf(
            // 잔고, 매수가능조회, 주식주문(매수·매도), 정정취소, 일별주문체결조회
            "8434R",
            "8908R",
            "0012U",
            "0011U",
            "0013U",
            "0081R",
        )

    fun matches(trId: String): Boolean = trId.length > 4 && trId.substring(1, 4) == "TTC" && trId.substring(4) in IDS
}

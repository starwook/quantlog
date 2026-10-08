package com.quantlog.broker

import com.fasterxml.jackson.databind.JsonNode

/** 한투가 `rt_cd` ≠ 0 으로 거절·실패를 알렸다. [code] 는 한투 `msg_cd`(예: 초당 한도 EGW00201). */
class KisApiException(
    message: String,
    val code: String? = null,
) : RuntimeException(message)

/** 한투 REST 응답 한 건. [body] 는 한투 JSON 원문, [trCont] 는 연속조회 응답 헤더(`tr_cont`, 다음 쪽이 있으면 M·F). */
data class KisReply(
    val body: JsonNode,
    val trCont: String = "",
)

/**
 * 한투 REST 통로. 앱은 한투 키가 없어 게이트웨이를 거친다 — 구현체(`gatewayclient`)는 요청을 그대로 전하고 한투 응답을 그대로 돌려줄 뿐이다.
 * 한투 문서대로 요청을 만들고 응답을 읽는 일은 [KisBrokerClient] 가 한다. 계좌번호(`CANO`·`ACNT_PRDT_CD`)는 넣지 않는다 — 게이트웨이가 끼운다.
 * [trCont] 는 연속조회 요청 헤더(첫 쪽은 비움, 다음 쪽은 "N"). [requestId] 가 있으면 같은 ID 의 재요청은 한 번만 나간다(주문·취소).
 */
interface KisRest {
    fun get(
        path: String,
        trId: String,
        params: Map<String, String>,
        trCont: String = "",
    ): KisReply

    fun post(
        path: String,
        trId: String,
        body: Map<String, String>,
        requestId: String? = null,
    ): KisReply
}

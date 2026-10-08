package com.quantlog.broker.kis

/** [code] 는 KIS 응답의 `msg_cd`. HTTP 오류·빈 응답처럼 코드가 없는 실패는 null. */
class KisApiException(message: String, cause: Throwable? = null, val code: String? = null) : RuntimeException(message, cause)

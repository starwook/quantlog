package com.quantlog.gateway

import com.quantlog.gateway.kis.KisProperties
import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Component

/** 허락되지 않은 곳에서 증권사 키로 게이트웨이를 띄우려 했다. */
class KisRunNotAllowedException(message: String) : IllegalStateException(message)

/**
 * 증권사 키가 있는 게이트웨이는 서버에서만 뜬다. 한투는 같은 앱키로 웹소켓 접속키를 새로 받으면 이전 키를 무효로 만들어,
 * 로컬에서 게이트웨이를 띄우는 순간 서버 게이트웨이의 체결통보 구독이 끊긴다(2026-10-13 실제 사고). 그래서 서버(`QUANTLOG_GATEWAY_ALLOW_KIS=true`)가
 * 아니면 키가 있을 때 시작을 거부한다. 시세·체결은 서버의 앱 화면과 DB 로 본다. 키가 없으면(테스트·키 없는 로컬) 그대로 뜬다.
 */
@Component
class KisRunGuard(
    private val kis: KisProperties,
    private val gateway: GatewayProperties,
) {
    @PostConstruct
    fun check() {
        if (kis.hasCredentials && !gateway.allowKis) {
            throw KisRunNotAllowedException(
                "증권사 키가 설정된 게이트웨이는 서버에서만 띄웁니다(quantlog.gateway.allow-kis=true). " +
                    "같은 앱키로 하나 더 띄우면 서버의 체결통보 구독이 끊깁니다. 로컬에서는 키를 비워 두세요.",
            )
        }
    }
}

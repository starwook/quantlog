package com.quantlog.gateway

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * 게이트웨이 — 증권사(KIS)와 닿는 모든 것을 맡는 서버. 거의 안 꺼지는 것이 목적이다(docs/서버-분리.md "최우선 원칙").
 * 기록·전달·실행만 한다: 받은 체결통보·잔고를 그대로 행으로 저장하고, 주문을 내고, 시세·알림을 앱에 넘긴다. 해석(평단·손익 계산 등)은 앱이 한다.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
class GatewayApplication

fun main(args: Array<String>) {
    runApplication<GatewayApplication>(*args)
}

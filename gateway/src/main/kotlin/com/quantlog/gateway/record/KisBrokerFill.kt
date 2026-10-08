package com.quantlog.gateway.record

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

/**
 * 증권사가 실시간으로 밀어 준 체결통보(접수·체결·정정·취소·거부)를 **원문 그대로** 한 건씩 쌓는 원장(append-only).
 * [body] 는 복호화한 `^` 구분 필드 한 건이다 — 게이트웨이는 해석하지 않고, 앱이 한투 공식 문서대로 읽는다.
 * 계좌 식별 필드(고객 ID·계좌번호·계좌명)만 비워서 저장한다. 앱이 읽는 계약이다(docs/contracts/README.md) — 컬럼은 추가만 한다.
 */
@Entity
@Table(name = "kis_broker_fill")
class KisBrokerFill(
    @Column(name = "received_at", nullable = false)
    val receivedAt: Instant,
    /** 실시간 TR ID 원문(국내 모의 H0STCNI9). 필드 순서가 TR 마다 다르다. */
    @Column(name = "tr_id", nullable = false, length = 20)
    val trId: String,
    @Column(nullable = false, length = 2000)
    val body: String,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set
}

interface KisBrokerFillRepository : JpaRepository<KisBrokerFill, Long>

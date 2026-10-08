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
 * 한투 주식당일분봉조회 응답을 **원문 그대로** 쌓는다(종목마다 한 번 받을 때 한 줄). 게이트웨이는 해석하지 않고, 앱이 한투 문서대로 읽어 자기 `minute_candle` 에 넣는다.
 * 한투는 당일 분봉만·한 번에 30건만 주므로 놓치지 않으려면 받는 족족 쌓아야 한다. 앱이 읽는 계약(docs/contracts/README.md).
 */
@Entity
@Table(name = "kis_minute_chart")
class KisMinuteChart(
    @Column(nullable = false, length = 20)
    val market: String,
    @Column(nullable = false, length = 20)
    val symbol: String,
    @Column(name = "received_at", nullable = false)
    val receivedAt: Instant,
    @Column(nullable = false, columnDefinition = "mediumtext")
    val body: String,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set
}

interface KisMinuteChartRepository : JpaRepository<KisMinuteChart, Long> {
    fun deleteByReceivedAtBefore(cutoff: Instant): Long
}

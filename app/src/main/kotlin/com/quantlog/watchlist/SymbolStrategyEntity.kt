package com.quantlog.watchlist

import com.quantlog.broker.Market
import com.quantlog.gatewayclient.WatchSymbolRow
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.OneToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.springframework.data.jpa.repository.JpaRepository
import java.math.BigDecimal

/**
 * 종목별 매매 설정. 오늘 살 종목, 익절·손절 %, 마틴게일 파라미터처럼 원칙이 바뀔 때마다 만지는 값을
 * 코드가 아니라 DB(`symbol_strategy` 테이블)에 둔다 — 값을 UPDATE 하면 재시작 없이 스케줄러의 다음 주기(1초)부터 반영된다.
 * 스케줄러는 매번 읽기 때문에 캐시가 없다. 이 테이블의 행 목록이 곧 감시 종목(분봉 수집·차트·구독 대상)이다 — 종목을 추가하려면 행을 넣으면 된다.
 *
 * 비율은 % 단위다(0.5 = 0.5%). 손절이 null 이면 보류(안 나감)다. 손절은 마틴게일 여부·단계와 무관하게 평단 기준 하나뿐이다.
 * 매수 옵션이 하나도 안 켜진 종목은 분봉만 모으고 사지 않는다. 아래 [SymbolStrategySeeder] 가 없는 행만 기본값으로 채운다(기존 값은 안 덮어씀).
 */
@Entity
@Table(name = "symbol_strategy", uniqueConstraints = [UniqueConstraint(columnNames = ["market", "symbol"])])
class SymbolStrategy(
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val market: Market,
    @Column(nullable = false, length = 20)
    val symbol: String,
    /** 화면·로그에 보여줄 이름. */
    @Column(name = "display_name", nullable = false, length = 100)
    var displayName: String,
    @Column(name = "take_profit_percent", nullable = false, precision = 10, scale = 4)
    var takeProfitPercent: BigDecimal,
    @Column(name = "stop_loss_percent", precision = 10, scale = 4)
    var stopLossPercent: BigDecimal?,
    /** true 면 보유 중에 마틴게일 규칙(MartingaleRule)으로 추가 매수한다. 아래 martingale_* 는 이때만 쓴다. */
    @Column(nullable = false)
    var martingale: Boolean,
    @Column(name = "martingale_drop_percent", nullable = false, precision = 10, scale = 4)
    var martingaleDropPercent: BigDecimal,
    @Column(name = "martingale_multiplier", nullable = false)
    var martingaleMultiplier: Int,
    @Column(name = "martingale_max_stages", nullable = false)
    var martingaleMaxStages: Int,
    /**
     * 옛 ETF 컬럼. ETF 여부는 이제 [watchSymbol] 이 갖는다. 이 컬럼은 NOT NULL 이라 매핑을 지우면 새 행 insert 가 깨져서 남겨 두고,
     * 외래키 연결([SymbolStrategyService.linkWatchSymbols]) 때 값을 옮기는 데만 읽는다.
     */
    @Column(name = "etf", nullable = false)
    var legacyEtf: Boolean = false,
    /** true 면 보유 수량과 상관없이 분봉 "저점 판단 진입"(SupportBounceEntryRule) 신호가 뜰 때마다 [supportBounceQuantity]주를 산다. 다른 옵션과 별개다. */
    @Column(name = "support_bounce_entry", nullable = false, columnDefinition = "bit default 0")
    var supportBounceEntry: Boolean = false,
    /** true 면 [periodicRebuyIntervalMinutes]분마다 계속 돌면서, 그 순간 보유가 0주이면 [periodicRebuyQuantity]주를 산다. 다른 옵션과 별개다. */
    @Column(name = "periodic_rebuy", nullable = false, columnDefinition = "bit default 0")
    var periodicRebuy: Boolean = false,
    /** 주기 재매수가 한 번에 사는 수량. 마틴게일은 보유 수량의 배수로 사므로 수량 설정이 없다. */
    @Column(name = "periodic_rebuy_quantity", nullable = false, columnDefinition = "int default 1")
    var periodicRebuyQuantity: Int = 1,
    /** 주기 재매수가 도는 간격(분). */
    @Column(name = "periodic_rebuy_interval_minutes", nullable = false, columnDefinition = "int default 5")
    var periodicRebuyIntervalMinutes: Int = 5,
    /** 저점 판단 진입이 한 번에 사는 수량. */
    @Column(name = "support_bounce_quantity", nullable = false, columnDefinition = "int default 1")
    var supportBounceQuantity: Int = 1,
    /**
     * 이 종목의 원본 행(`watch_symbol`). 게이트웨이가 읽는 계약 테이블이고 ETF 여부도 여기 있다. 종목을 추가·삭제하면 이 행과 같은 트랜잭션에서 함께 바뀐다.
     * 기존 행은 null 일 수 있고 시작 시 채워진다.
     */
    @OneToOne
    @JoinColumn(name = "watch_symbol_id", unique = true)
    var watchSymbol: WatchSymbolRow? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    /** ETF 면 true. 국내 ETF 는 증권거래세가 없어서 모킹 체결의 제세금 계산이 달라진다. */
    val etf: Boolean get() = watchSymbol?.etf ?: false
}

interface SymbolStrategyRepository : JpaRepository<SymbolStrategy, Long> {
    fun findByMarketAndSymbol(
        market: Market,
        symbol: String,
    ): SymbolStrategy?
}

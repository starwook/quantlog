package com.quantlog.futures

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.math.BigDecimal
import java.time.LocalDate

/**
 * 하루 한 번 모은 옵션 행사가 하나의 콜·풋 값. 한투 전광판은 100행에서 끊겨 지수 근처가 빠지므로(실측) 행사가마다 따로 조회해 쌓는다.
 * [maturity] 는 월물(`2026-11`), [underlying] 은 수집 때의 KOSPI200 지수.
 */
@Entity
@Table(
    name = "option_open_interest",
    uniqueConstraints = [UniqueConstraint(columnNames = ["trade_date", "maturity", "strike"])],
)
class OptionOpenInterest(
    @Column(name = "trade_date", nullable = false)
    val tradeDate: LocalDate,
    @Column(nullable = false, length = 7)
    val maturity: String,
    @Column(nullable = false, precision = 10, scale = 2)
    val strike: BigDecimal,
    @Column(precision = 10, scale = 2)
    val underlying: BigDecimal?,
    @Column(name = "call_price", precision = 10, scale = 2)
    val callPrice: BigDecimal?,
    @Column(name = "call_oi")
    val callOi: Long?,
    @Column(name = "call_oi_change")
    val callOiChange: Long?,
    @Column(name = "call_volume")
    val callVolume: Long?,
    @Column(name = "put_price", precision = 10, scale = 2)
    val putPrice: BigDecimal?,
    @Column(name = "put_oi")
    val putOi: Long?,
    @Column(name = "put_oi_change")
    val putOiChange: Long?,
    @Column(name = "put_volume")
    val putVolume: Long?,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    fun toStrike(): OptionStrike =
        OptionStrike(
            strike,
            call = callOi?.let { OptionQuote(callPrice ?: BigDecimal.ZERO, it, callOiChange ?: 0, callVolume ?: 0) },
            put = putOi?.let { OptionQuote(putPrice ?: BigDecimal.ZERO, it, putOiChange ?: 0, putVolume ?: 0) },
        )

    companion object {
        fun of(
            tradeDate: LocalDate,
            maturity: String,
            underlying: BigDecimal?,
            strike: OptionStrike,
        ) = OptionOpenInterest(
            tradeDate,
            maturity,
            strike.strike,
            underlying,
            strike.call?.price,
            strike.call?.openInterest,
            strike.call?.openInterestChange,
            strike.call?.volume,
            strike.put?.price,
            strike.put?.openInterest,
            strike.put?.openInterestChange,
            strike.put?.volume,
        )
    }
}

interface OptionOpenInterestRepository : JpaRepository<OptionOpenInterest, Long> {
    fun findAllByTradeDateAndMaturity(
        tradeDate: LocalDate,
        maturity: String,
    ): List<OptionOpenInterest>

    @Query("select max(o.tradeDate) from OptionOpenInterest o where o.maturity = :maturity")
    fun latestTradeDate(maturity: String): LocalDate?

    @Modifying
    @Query("delete from OptionOpenInterest o where o.tradeDate = :tradeDate and o.maturity = :maturity")
    fun deleteSnapshot(
        tradeDate: LocalDate,
        maturity: String,
    )
}

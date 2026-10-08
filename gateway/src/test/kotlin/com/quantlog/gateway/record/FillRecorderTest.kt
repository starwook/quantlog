package com.quantlog.gateway.record

import com.quantlog.gateway.broker.FillNotice
import com.quantlog.gateway.stream.StreamHub
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import java.math.BigDecimal

class FillRecorderTest {
    private val repository = Mockito.mock(BrokerFillRepository::class.java)
    private val hub = Mockito.mock(StreamHub::class.java)
    private val balances = Mockito.mock(BalanceRecorder::class.java)
    private val recorder = FillRecorder(repository, hub, balances)

    private fun notice(filledFlag: String) =
        FillNotice(
            symbol = "005930", orderNo = "0001", originalOrderNo = "", sellBuyCode = "02", filledFlag = filledFlag, acceptFlag = "2",
            refuseFlag = "0", filledQuantity = BigDecimal("3"), filledPrice = BigDecimal("70100"), orderQuantity = BigDecimal("3"),
            orderPrice = BigDecimal("70200"), time = "093000",
        )

    private fun saveReturningId(id: Long) {
        Mockito.`when`(repository.save(Mockito.any(BrokerFill::class.java))).thenAnswer {
            it.getArgument<BrokerFill>(0).also {
                    row ->
                setId(row, id)
            }
        }
    }

    private fun setId(
        row: BrokerFill,
        id: Long,
    ) {
        BrokerFill::class.java.getDeclaredField("id").apply { isAccessible = true }.set(row, id)
    }

    @Test
    fun `체결통보는 원장에 한 줄 쌓고 스트림 알림과 잔고 갱신을 요청한다`() {
        saveReturningId(7)
        recorder.onFillNotice(notice("2"))

        val captor = ArgumentCaptor.forClass(BrokerFill::class.java)
        Mockito.verify(repository).save(captor.capture())
        assertEquals("0001", captor.value.orderNo)
        assertEquals(0, BigDecimal("70100").compareTo(captor.value.filledPrice))
        Mockito.verify(hub).fillRecorded(7)
        Mockito.verify(balances).requestRefresh()
    }

    @Test
    fun `접수 통보는 기록하되 잔고 갱신은 요청하지 않는다`() {
        saveReturningId(8)
        recorder.onFillNotice(notice("1"))

        Mockito.verify(hub).fillRecorded(8)
        Mockito.verify(balances, Mockito.never()).requestRefresh()
    }

    @Test
    fun `저장이 계속 실패해도 예외를 던지지 않고 알림도 보내지 않는다`() {
        Mockito.`when`(repository.save(Mockito.any(BrokerFill::class.java))).thenThrow(RuntimeException("db down"))
        recorder.onFillNotice(notice("2"))

        Mockito.verify(repository, Mockito.times(3)).save(Mockito.any(BrokerFill::class.java))
        Mockito.verifyNoInteractions(hub)
    }
}

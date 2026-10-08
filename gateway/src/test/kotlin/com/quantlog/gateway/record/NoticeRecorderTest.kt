package com.quantlog.gateway.record

import com.quantlog.gateway.kis.BrokerNoticeReceived
import com.quantlog.gateway.stream.StreamHub
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito

class NoticeRecorderTest {
    private val repository = Mockito.mock(BrokerNoticeRepository::class.java)
    private val hub = Mockito.mock(StreamHub::class.java)
    private val balances = Mockito.mock(BalanceRecorder::class.java)
    private val recorder = NoticeRecorder(repository, hub, balances)

    private fun saveReturningId(id: Long) {
        Mockito.`when`(repository.save(Mockito.any(BrokerNotice::class.java))).thenAnswer {
            it.getArgument<BrokerNotice>(0).also { row ->
                BrokerNotice::class.java.getDeclaredField("id").apply { isAccessible = true }.set(row, id)
            }
        }
    }

    @Test
    fun `통보 원문을 그대로 한 줄 쌓고 스트림 알림과 잔고 갱신을 요청한다`() {
        saveReturningId(7)
        recorder.onNotice(BrokerNoticeReceived("H0STCNI9", "^^0001^^02"))

        val captor = ArgumentCaptor.forClass(BrokerNotice::class.java)
        Mockito.verify(repository).save(captor.capture())
        assertEquals("H0STCNI9", captor.value.trId)
        assertEquals("^^0001^^02", captor.value.body)
        Mockito.verify(hub).fillRecorded(7)
        Mockito.verify(balances).requestRefresh()
    }

    @Test
    fun `저장이 계속 실패해도 예외를 던지지 않고 알림도 보내지 않는다`() {
        Mockito.`when`(repository.save(Mockito.any(BrokerNotice::class.java))).thenThrow(RuntimeException("db down"))
        recorder.onNotice(BrokerNoticeReceived("H0STCNI9", "x"))

        Mockito.verify(repository, Mockito.times(3)).save(Mockito.any(BrokerNotice::class.java))
        Mockito.verifyNoInteractions(hub)
    }
}

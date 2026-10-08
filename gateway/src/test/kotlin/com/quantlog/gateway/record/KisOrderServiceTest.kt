package com.quantlog.gateway.record

import com.quantlog.gateway.kis.KisApiClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.dao.DataIntegrityViolationException

class KisOrderServiceTest {
    private val rows = mutableMapOf<String, KisOrder>()
    private val repository =
        Mockito.mock(KisOrderRepository::class.java).also { repo ->
            Mockito.`when`(repo.findByRequestId(Mockito.anyString())).thenAnswer { rows[it.getArgument<String>(0)] }
            Mockito.`when`(repo.save(Mockito.any(KisOrder::class.java))).thenAnswer {
                it.getArgument<KisOrder>(0).also {
                        row ->
                    rows[row.requestId] = row
                }
            }
        }
    private val service = KisOrderService(repository)
    private var sends = 0
    private val ok = KisApiClient.RawResponse(200, """{"rt_cd":"0","output":{"ODNO":"1"}}""", null)

    private fun forward(
        requestId: String = "r1",
        send: () -> KisApiClient.RawResponse = { ok },
    ) = service.forward(requestId, "VTTC0012U", "/uapi/order", """{"PDNO":"005930"}""") {
        sends++
        send()
    }

    @Test
    fun `응답 원문을 저장하고 같은 요청 ID 는 또 보내지 않고 저장된 응답을 돌려준다`() {
        assertEquals(ok.body, forward().body)
        val again = forward()

        assertEquals(1, sends)
        assertEquals(ok.body, again.body)
        assertEquals(KisOrder.DONE, rows.getValue("r1").status)
        assertEquals(ok.body, rows.getValue("r1").responseBody)
    }

    @Test
    fun `한투가 거절로 답해도 응답이라 그대로 저장하고 재생한다`() {
        val rejected = KisApiClient.RawResponse(200, """{"rt_cd":"1","msg_cd":"APBK0013","msg1":"주문 불가"}""", null)
        forward { rejected }

        assertEquals(rejected.body, forward().body)
        assertEquals(1, sends)
    }

    @Test
    fun `응답을 못 받으면 FAILED 로 남기고 같은 ID 로 다시 오면 실패 사유로 거절한다`() {
        assertThrows<IllegalStateException> { forward { error("연결 실패") } }

        assertEquals(KisOrder.FAILED, rows.getValue("r1").status)
        assertThrows<OrderFailedException> { forward() }
        assertEquals(1, sends)
    }

    @Test
    fun `보내는 중인 채 남은 요청은 결과를 모른다고 거절한다`() {
        rows["r1"] = KisOrder("r1", "VTTC0012U", "/uapi/order", "{}", KisOrder.SENDING)

        assertThrows<OrderResultUnknownException> { forward() }
        assertEquals(0, sends)
    }

    @Test
    fun `동시에 같은 ID 가 먼저 들어갔으면 보내지 않고 그쪽 결과를 따른다`() {
        Mockito.`when`(repository.save(Mockito.any(KisOrder::class.java))).thenAnswer {
            rows["r1"] = KisOrder("r1", "VTTC0012U", "/uapi/order", "{}", KisOrder.SENDING)
            throw DataIntegrityViolationException("dup")
        }

        assertThrows<OrderResultUnknownException> { forward() }
        assertEquals(0, sends)
    }
}

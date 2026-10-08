package com.quantlog.gateway.record

import com.quantlog.gateway.kis.KisApiClient
import mu.KotlinLogging
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import java.time.Instant

private val log = KotlinLogging.logger {}

/** 같은 요청 ID 의 주문이 이미 "보내는 중"이라 결과를 모른다. 맹목적으로 다시 보내지 않고, 앱이 주문 상태를 조회해 확정하게 한다. */
class OrderResultUnknownException(message: String) : RuntimeException(message)

/** 같은 요청 ID 로 이미 실패한 주문. 저장해 둔 실패 사유를 그대로 돌려준다(새 시도는 새 요청 ID 로). */
class OrderFailedException(message: String) : RuntimeException(message)

/**
 * 주문·취소를 한투로 내고 요청·응답 원문을 [KisOrder] 에 남긴다. **호출 전에 SENDING 을 먼저 쓴다** — 같은 요청 ID 로 다시 오면 저장된 응답을
 * 돌려줄 뿐 또 보내지 않는다. 한투가 거절(`rt_cd` ≠ 0)로 답해도 응답은 응답이라 그대로 저장·재생한다. 응답 자체를 못 받은 경우(연결 실패 등)만 FAILED.
 * 트랜잭션으로 감싸지 않는다(SENDING 이 호출 전에 커밋돼야 한다).
 */
@Service
class KisOrderService(
    private val repository: KisOrderRepository,
) {
    fun forward(
        requestId: String,
        trId: String,
        path: String,
        requestBody: String,
        send: () -> KisApiClient.RawResponse,
    ): KisApiClient.RawResponse {
        repository.findByRequestId(requestId)?.let { return replay(it) }
        val row =
            try {
                repository.save(KisOrder(requestId, trId, path, requestBody, KisOrder.SENDING))
            } catch (e: DataIntegrityViolationException) {
                return replay(requireNotNull(repository.findByRequestId(requestId)))
            }
        return try {
            send().also { response ->
                finish(row, KisOrder.DONE) {
                    httpStatus = response.status
                    responseBody = response.body
                }
            }
        } catch (e: Exception) {
            finish(row, KisOrder.FAILED) { error = (e.message ?: e.javaClass.simpleName).take(MAX) }
            throw e
        }
    }

    private fun replay(row: KisOrder): KisApiClient.RawResponse =
        when (row.status) {
            KisOrder.DONE -> KisApiClient.RawResponse(row.httpStatus ?: 200, row.responseBody.orEmpty(), null)
            KisOrder.FAILED -> throw OrderFailedException(row.error ?: "이전 요청이 실패했다")
            else -> throw OrderResultUnknownException("요청 ${row.requestId} 는 증권사로 보내는 중이었고 결과를 모른다 — 주문 상태를 조회해 확정할 것")
        }

    private fun finish(
        row: KisOrder,
        status: String,
        fill: KisOrder.() -> Unit,
    ) {
        row.status = status
        row.updatedAt = Instant.now()
        row.fill()
        runCatching { repository.save(row) }
            .onFailure { log.error(it) { "[주문 기록] 결과를 DB 에 못 썼다: 요청 ${row.requestId} → $status" } }
    }

    private companion object {
        const val MAX = 500
    }
}

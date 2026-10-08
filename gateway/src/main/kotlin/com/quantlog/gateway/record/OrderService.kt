package com.quantlog.gateway.record

import com.quantlog.gateway.broker.BrokerClient
import com.quantlog.gateway.broker.CancelRequest
import com.quantlog.gateway.broker.OrderReceipt
import com.quantlog.gateway.broker.OrderRequest
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
 * 주문·취소를 증권사로 내고 그 기록을 남긴다. **호출 전에 SENDING 을 먼저 쓴다** — 응답을 못 받아도(타임아웃·크래시) "보내려 했다"가 남고,
 * 같은 요청 ID 로 다시 오면 저장된 결과를 돌려줄 뿐 또 보내지 않는다. 트랜잭션으로 감싸지 않는다(SENDING 이 호출 전에 커밋돼야 한다).
 */
@Service
class OrderService(
    private val broker: BrokerClient,
    private val repository: BrokerOrderRepository,
) {
    fun place(
        requestId: String,
        order: OrderRequest,
    ): OrderReceipt {
        existing(requestId)?.let { return resolveExisting(it) }
        val row =
            claim(
                BrokerOrder(
                    requestId, "PLACE", order.market.name, order.symbol, order.side.name, order.quantity, order.limitPrice, null,
                    BrokerOrder.SENDING,
                ),
            ) ?: return resolveExisting(requireNotNull(existing(requestId)))
        return try {
            val receipt = broker.placeOrder(order)
            finish(row, BrokerOrder.SENT) {
                orderNo = receipt.orderNo
                branchNo = receipt.branchNo
                message = receipt.message.take(MAX)
            }
            receipt
        } catch (e: Exception) {
            finish(row, BrokerOrder.FAILED) { error = (e.message ?: e.javaClass.simpleName).take(MAX) }
            throw e
        }
    }

    fun cancel(
        requestId: String,
        request: CancelRequest,
    ) {
        existing(requestId)?.let {
            resolveExisting(it)
            return
        }
        val row =
            claim(
                BrokerOrder(
                    requestId, "CANCEL", request.market.name, request.symbol, null, request.quantity, request.limitPrice, request.orderNo,
                    BrokerOrder.SENDING,
                ),
            )
        if (row == null) {
            resolveExisting(requireNotNull(existing(requestId)))
            return
        }
        try {
            broker.cancelOrder(request)
            finish(row, BrokerOrder.SENT) {}
        } catch (e: Exception) {
            finish(row, BrokerOrder.FAILED) { error = (e.message ?: e.javaClass.simpleName).take(MAX) }
            throw e
        }
    }

    private fun existing(requestId: String): BrokerOrder? = repository.findByRequestId(requestId)

    /** 새 행을 쓴다. 동시에 같은 요청 ID 가 먼저 들어갔으면 null. */
    private fun claim(row: BrokerOrder): BrokerOrder? =
        try {
            repository.save(row)
        } catch (e: DataIntegrityViolationException) {
            null
        }

    private fun resolveExisting(row: BrokerOrder): OrderReceipt =
        when (row.status) {
            BrokerOrder.SENT -> OrderReceipt(row.orderNo.orEmpty(), row.message.orEmpty(), row.branchNo.orEmpty())
            BrokerOrder.FAILED -> throw OrderFailedException(row.error ?: "이전 요청이 실패했다")
            else -> throw OrderResultUnknownException("요청 ${row.requestId} 는 증권사로 보내는 중이었고 결과를 모른다 — 주문 상태를 조회해 확정할 것")
        }

    private fun finish(
        row: BrokerOrder,
        status: String,
        fill: BrokerOrder.() -> Unit,
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

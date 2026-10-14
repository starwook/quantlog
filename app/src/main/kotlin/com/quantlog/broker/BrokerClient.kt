package com.quantlog.broker

import java.math.BigDecimal

interface BrokerClient {
    /** 현재가와 그 가격대의 호가 단위. */
    fun quote(
        market: Market,
        symbol: String,
    ): Quote

    /** 전일 종가. 실시간 시세에는 안 실려 와서 REST 로만 구한다. 구현체가 모르거나 값이 없으면 null. */
    fun previousClose(
        market: Market,
        symbol: String,
    ): BigDecimal? = null

    /**
     * 실제 체결가(평균). 국내 체결내역(inquire-daily-ccld)으로 조회. 못 구하면 null.
     * 아직 체결 전이거나 조회 시점에 반영 안 됐으면 null — placeOrder 직후 바로 호출하면 없을 수 있으니
     * 호출 전 약간 기다린다 (2026-09-29 실측: 삼성전자 273,000원 지정가 매수 → 실제 체결 272,000원,
     * 지정가와 체결가가 다를 수 있다는 걸 실제로 확인함).
     */
    fun filledPrice(
        market: Market,
        orderNo: String,
    ): BigDecimal?

    /**
     * 주문 상태. [quantity] 는 주문 수량 — 그만큼 전부 체결돼야 [OrderStatus.Filled] 다.
     * 기본 구현은 [filledPrice] 만 보고 체결/모름으로 가른다(미체결을 구분 못 하는 구현체용).
     */
    fun orderStatus(
        market: Market,
        orderNo: String,
        quantity: Int,
    ): OrderStatus = filledPrice(market, orderNo)?.let { OrderStatus.Filled(it) } ?: OrderStatus.Unknown

    /**
     * 오늘 접수된 주문들의 체결 누적(주문번호별, 체결이 0 인 주문도 포함). 체결통보를 놓쳤을 때(한투 웹소켓 끊김·서버 재시작) 빠진 체결을 채우는 데 쓴다.
     * 지원하지 않는 구현체는 빈 목록.
     */
    fun todayOrderFills(market: Market): List<OrderFillTotal> = emptyList()

    /** 오늘 주문 한 건의 체결 누적. 증권사 조회에 아직 안 잡히면 null. 조회 자체가 실패하면 예외. */
    fun orderFillTotal(
        market: Market,
        orderNo: String,
    ): OrderFillTotal? = todayOrderFills(market).firstOrNull { it.orderNo == orderNo }

    fun buyingPower(
        market: Market,
        symbol: String,
        price: BigDecimal,
    ): BuyingPower

    /** 수량 0인 종목은 제외한다. */
    fun holdings(market: Market): List<Holding>

    /**
     * 우리 DB 보유와 대조할 증권사 잔고(당일 누적 매수·매도 수량 포함). 증권사 잔고가 정본인 구현체만 준다 — 모킹 체결처럼 앱이 스스로 계산하는 구현체는 null.
     * 체결조회로 맞춘 뒤 거래를 다시 열기 전에 확인하는 데 쓴다([com.quantlog.position.FillReconcileService]).
     */
    fun holdingsForReconcile(market: Market): List<Holding>? = null

    fun placeOrder(order: OrderRequest): OrderReceipt

    /** 미체결 주문을 취소한다. 이미 체결됐으면 증권사가 거부한다. 취소를 지원하지 않는 구현체는 예외. */
    fun cancelOrder(request: CancelRequest): Unit = throw UnsupportedOperationException("이 브로커는 주문 취소를 지원하지 않습니다")
}

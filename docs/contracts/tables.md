# 공유 DB 테이블

같은 MySQL(`quantlog`)을 쓴다. 컬럼은 **추가만** 한다. 쓰는 쪽만 값을 바꾼다(앱의 읽기 전용 엔티티는 `@Immutable`).

| 테이블 | 쓰는 쪽 | 읽는 쪽 | 내용 |
|---|---|---|---|
| `broker_fill` | 게이트웨이 | 앱 | 체결통보 원장(append-only). `id, received_at, symbol, order_no, original_order_no, sell_buy_code, filled_flag, accept_flag, refuse_flag, filled_quantity, filled_price, order_quantity, order_price, notice_time` — 증권사가 준 필드 그대로, 해석 없음 |
| `broker_balance` | 게이트웨이 | 앱 | 잔고 스냅샷, 종목당 1행. `market, symbol, name, quantity, average_price, current_price, updated_at`. 회차마다 한 트랜잭션으로 교체 |
| `broker_balance_meta` | 게이트웨이 | 앱 | 1행. `seq`(회차), `fetched_at`(증권사 호출 **전** 시각), `completed_at` |
| `broker_order` | 게이트웨이 | (게이트웨이) | 주문·취소 요청 기록과 멱등. `request_id`(유일), `status` SENDING/SENT/FAILED, … |
| `gateway_instance` | 게이트웨이 | 앱 | 1행. 단일 실행 임대(`instance_id, lease_until`)와 하트비트(`heartbeat_at, ws_connected_at, live_symbols, contract_version`) |
| `watch_symbol` | **앱** | 게이트웨이 | 구독·분봉 수집 종목의 **원본**. `market, symbol, etf`. 앱 `symbol_strategy.watch_symbol_id` 가 이 행을 외래키로 가리키고, 종목 추가·삭제는 같은 트랜잭션에서 둘을 함께 바꾼 뒤 `POST /api/watch/refresh` 로 게이트웨이에 알린다 |
| `minute_candle` | 게이트웨이 | 앱 | 분봉 (양쪽이 같은 형식으로 선언) |
| `error_log` | 양쪽 | 앱(화면·알림) | 게이트웨이는 `logger` 가 `com.quantlog.gateway` 로 시작 |
| `broker_projection_cursor` | 앱 | 앱 | 앱이 원장·스냅샷을 어디까지 처리했는지 (`fill`=마지막 id, `balance`=마지막 seq) |

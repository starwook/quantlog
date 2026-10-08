# 공유 DB 테이블

같은 MySQL(`quantlog`)을 쓴다. 컬럼은 **추가만** 한다. 쓰는 쪽만 값을 바꾼다(앱의 읽기 전용 엔티티는 `@Immutable`).

| 테이블 | 쓰는 쪽 | 읽는 쪽 | 내용 |
|---|---|---|---|
| `broker_notice` | 게이트웨이 | 앱 | 체결통보 원장(append-only). `id, received_at, tr_id, body` — `body` 는 한투 실시간 체결통보를 복호화한 `^` 구분 원문 한 건(메시지 헤더의 건수로 나눈 것). **게이트웨이는 해석하지 않고**, 고객 ID·계좌번호·계좌명 칸(0·1·17번)만 비운다. 앱이 한투 문서(docs/kis-api/field-reference.md 3절)대로 읽는다(`KisFillNoticeParser`). 옛 `broker_fill` 은 쓰지 않는다 |
| `broker_balance` | 게이트웨이 | 앱 | 잔고 스냅샷, 종목당 1행. `market, symbol, name, quantity, average_price, current_price, updated_at`. 회차마다 한 트랜잭션으로 교체 |
| `broker_balance_meta` | 게이트웨이 | 앱 | 1행. `seq`(회차), `fetched_at`(증권사 호출 **전** 시각), `completed_at` |
| `broker_order` | 게이트웨이 | (게이트웨이) | 주문·취소 요청 기록과 멱등. `request_id`(유일), `status` SENDING/SENT/FAILED, … |
| `gateway_instance` | 게이트웨이 | 앱 | 1행. 단일 실행 임대(`instance_id, lease_until`)와 하트비트(`heartbeat_at, ws_connected_at, live_symbols, contract_version`) |
| `watch_symbol` | **앱** | 게이트웨이 | 구독·분봉 수집 종목의 **원본**. `market, symbol, etf`. 앱 `symbol_strategy.watch_symbol_id` 가 이 행을 외래키로 가리키고, 종목 추가·삭제는 같은 트랜잭션에서 둘을 함께 바꾼 뒤 `POST /api/watch/refresh` 로 게이트웨이에 알린다 |
| `minute_candle` | 게이트웨이 | 앱 | 분봉 (양쪽이 같은 형식으로 선언) |
| `error_log` | 양쪽 | 앱(화면·알림) | 게이트웨이는 `logger` 가 `com.quantlog.gateway` 로 시작 |
| `broker_projection_cursor` | 앱 | 앱 | 앱이 원장·스냅샷을 어디까지 처리했는지 (`notice`=`broker_notice` 마지막 id, `balance`=마지막 seq. 옛 `fill` 행은 쓰지 않는다) |

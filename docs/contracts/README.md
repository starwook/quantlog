# 게이트웨이 ↔ 앱 계약

게이트웨이(`gateway`)와 앱(`app`)은 코드를 공유하지 않는다. 합의한 것은 이 한 장뿐이다. 설계 이유·원칙은 [../서버-분리.md](../서버-분리.md).
**게이트웨이는 한투(KIS)에서 받은 것을 해석하지 않고 원문 그대로 전달하거나 저장한다.** 내용(필드 이름·응답 형식)은 한투 공식 문서([../kis-api/](../kis-api/))를 따르고, 해석은 앱이 한다 —
그래서 한투 쪽 형식이 바뀌어도 앱만 고친다. 이 계약에는 "통로" 형식만 적는다. **추가만 하고 삭제·의미 변경은 하지 않는다**(게이트웨이를 다시 띄울 일을 만들지 않으려고).

계약 버전: **2**(`gateway_instance.contract_version`, 앱의 `APP_CONTRACT_VERSION`). 2(2026-10-08): 데이터를 초기화하면서 한 번 예외로 형식을 바꿨다 — 해석한 컬럼·API 를 원문 통로로
(`broker_fill`→`kis_broker_fill`, `broker_balance`(+meta)→`kis_balance`, `minute_candle` 쓰기→`kis_minute_chart`, `broker_order`→`kis_order`, 전용 REST API→`/api/kis/**`, 스트림 `tick`·`candle`→`raw`). 배포 전이라 버전은 그대로 2.

선언 위치: 게이트웨이 `com.quantlog.gateway.record.*`·`lease.*`·`api.*`, 앱 `com.quantlog.gatewayclient.*`·`com.quantlog.broker.Kis*`. 형식을 바꿀 때는 양쪽 테스트(`KisPassthroughApiTest`/`GatewayClientTest`, `GatewayStreamClientTest`)를 같이 고친다.

## 1. 한투 REST 통로 (앱 → 게이트웨이, HTTP)
베이스 `http://gateway:8081`. 공유 토큰 헤더 `X-Gateway-Token`(게이트웨이에 토큰이 설정돼 있으면 필수, 틀리면 401). 사설망에서만 바인딩한다.

`GET|POST /api/kis/<한투 경로>` — 예: `GET /api/kis/uapi/domestic-stock/v1/quotations/inquire-price?FID_INPUT_ISCD=005930`. 경로는 `/uapi/` 로 시작하는 한투 경로만 받는다(아니면 422).

| | 요청 | 응답 |
|---|---|---|
| 형식 | 한투 문서 그대로 — GET 은 쿼리, POST 는 JSON 본문(문자열 값) | **한투가 준 HTTP 상태·본문(JSON 원문)·`tr_cont` 헤더 그대로** (오류 `rt_cd`≠0 도 그대로) |
| 헤더 | `tr_id`(필수, 한투 TR ID) · `tr_cont`(연속조회, 첫 쪽은 생략, 다음 쪽은 `N`) · `X-Call-Priority: urgent`(즉발) · `X-Request-Id`(주문·취소) | `tr_cont`(M·F 면 다음 쪽 있음) |

게이트웨이가 하는 일(해석 아님): ① 토큰 발급·갱신 ② 호출 간격 제한과 우선순위(`urgent` 는 대기열에서 먼저) ③ 초당 한도 초과(EGW00201/EGW00215) 한 번 재시도, 그래도 오류면 그 응답 그대로
④ **계좌번호 끼우기** — 앱은 계좌번호를 모른다(시크릿). 계좌가 필요한 TR(`?TTC8434R` 잔고, `8908R` 매수가능, `0012U`·`0011U` 주문, `0013U` 취소, `0081R` 당일 체결조회; `?` 는 모의 V·실전 T)에는 `CANO`·`ACNT_PRDT_CD` 를 게이트웨이가 넣고, 앱이 보낸 값은 덮어쓴다.

앱은 응답에 `rt_cd` 가 있으면 한투 응답으로, 없으면 게이트웨이 오류로 읽는다.

**게이트웨이 오류 `{error, code}`** (한투 응답이 아닌 것)
| 상태 | code | 뜻 |
|---|---|---|
| 502 | null | 한투 호출 재시도 초과 등 |
| 502 | `PREVIOUS_FAILED` | 같은 요청 ID 가 이미 실패(응답을 못 받음)로 기록됨 |
| 409 | `RESULT_UNKNOWN` | 같은 요청 ID 가 "보내는 중"인 채 결과를 모름 — 앱이 당일 주문조회로 확정 |
| 422 | `INVALID` | 경로·`tr_id` 오류 |
| 401 | — | 토큰 불일치 |

### 멱등 (주문·취소)
앱이 주문·취소마다 새 `X-Request-Id` 를 붙인다. 게이트웨이는 한투 호출 **전에** `kis_order` 에 SENDING 으로 기록하고, 응답을 받으면 DONE 과 함께 **응답 원문**을 저장한다(한투가 거절해도 응답이라 DONE).
같은 ID 가 다시 오면 저장된 응답을 돌려주고 또 보내지 않는다. SENDING 인 채면 409 `RESULT_UNKNOWN`, 응답을 못 받고 실패했으면 502 `PREVIOUS_FAILED`.
응답을 못 받았으면 앱은 새 ID 로 재주문하지 말고 같은 ID 로 다시 묻거나 당일 체결조회로 확정한다.

### 게이트웨이 전용 (한투가 아님)
| 메서드 경로 | 응답 |
|---|---|
| `POST /api/watch/refresh` | `{liveSymbols: [..]}` — `watch_symbol` 을 다시 읽어 실시간 구독을 즉시 맞춘다. 실패해도 게이트웨이가 10초 주기로 같은 일을 한다 |
| `GET /api/health` | `{instanceId, startedAt, contractVersion, kisCredentials, wsConnected, wsConnectedAt, liveSymbols, now}` |

## 2. 웹소켓 스트림 `/stream` (게이트웨이 → 앱)
`ws://gateway:8081/stream`, 연결 시 헤더 `X-Gateway-Token`. 앱은 아무것도 보내지 않는다. 모든 메시지는 `{"type": ..., ...}` JSON. 앱은 3초마다 연결을 확인해 다시 붙고, 20초 넘게 메시지(ping 포함)가 없으면 끊고 다시 붙는다.

| type | 필드 | 설명 |
|---|---|---|
| `hello` | `instanceId, startedAt, contractVersion` | 연결 직후 한 번 |
| `ping` | `at` | 5초마다 |
| `raw` | `data` | 한투 실시간 시세 메시지 원문 한 건(`0|H0STCNT0|001|...`). 해석(틱·분봉·최신가)은 앱이 한투 문서대로. 묶지 않고 모두 보내되, 느린 앱이면 새 시세를 버린다(연결 대기 5000건 초과) |
| `fill` | `id` | `kis_broker_fill` 에 새 행(id) — 빠른 알림일 뿐 정본은 DB |
| `balance` | `id` | `kis_balance` 에 새 행(id) — 위와 같음 |

## 3. 공유 DB 테이블
같은 MySQL(`quantlog`)을 쓴다. 컬럼은 **추가만** 한다. 쓰는 쪽만 값을 바꾼다(앱의 읽기 전용 엔티티는 `@Immutable`). 한투 원문을 담는 테이블은 `kis_` 로 시작한다.

| 테이블 | 쓰는 쪽 → 읽는 쪽 | 내용 |
|---|---|---|
| `kis_broker_fill` | 게이트웨이 → 앱 | 체결통보 원장(append-only). `id, received_at, tr_id, body` — `body` 는 한투 실시간 체결통보를 복호화한 `^` 구분 원문 한 건(헤더 건수로 나눈 것). 고객 ID·계좌번호·계좌명 칸(0·1·17번)만 비운다. 앱 `KisFillNoticeParser`(docs/kis-api/field-reference.md 3절) |
| `kis_balance` | 게이트웨이 → 앱 | 잔고조회(VTTC8434R) 응답 원문, 회차마다 한 줄. `id, fetched_at(한투 호출 **전** 시각), received_at, body`. 앱은 가장 큰 id 한 줄만 쓴다. 하루 지난 줄은 지운다. 앱 `KisBalanceParser` |
| `kis_minute_chart` | 게이트웨이 → 앱 | 당일분봉조회(FHKST03010200) 응답 원문. `id, market, symbol, received_at, body`(종목마다 한 번 받을 때 한 줄, 직전과 같은 본문은 건너뜀). 하루 지난 줄은 지운다. 앱 `KisMinuteChartParser` 가 자기 `minute_candle` 에 넣는다 |
| `kis_order` | 게이트웨이 → (게이트웨이) | 주문·취소 요청·응답 원문과 멱등. `request_id`(유일), `tr_id, path, request_body, status`(SENDING/DONE/FAILED), `http_status, response_body, error` |
| `gateway_instance` | 게이트웨이 → 앱 | 1행. 단일 실행 임대(`instance_id, lease_until`)와 하트비트(`heartbeat_at, ws_connected_at, live_symbols, contract_version`) |
| `watch_symbol` | **앱** → 게이트웨이 | 구독·분봉 수집 종목의 **원본**(관심종목 전체. 보유 종목은 모두 여기 있다 — 앱이 보유 중인 종목의 삭제를 막는다). `market, symbol, etf`. 앱 `symbol_strategy.watch_symbol_id` 가 이 행을 외래키로 가리키고, 종목 추가·삭제는 같은 트랜잭션에서 둘을 함께 바꾼 뒤 `POST /api/watch/refresh` 로 알린다 |
| `minute_candle` | **앱만** | 분봉. 앱이 실시간 시세로 만든 것과 `kis_minute_chart` 에서 푼 것이 함께 들어간다(게이트웨이는 쓰지 않는다) |
| `paper_order` | 앱만 | 모킹 체결(`QUANTLOG_BROKER_TYPE=paper`) 기록 |
| `error_log` | 양쪽 → 앱(화면·알림) | 게이트웨이는 `logger` 가 `com.quantlog.gateway` 로 시작 |
| `broker_projection_cursor` | 앱 → 앱 | 앱이 원장을 어디까지 처리했는지(`kis_broker_fill`·`kis_balance`·`kis_minute_chart` = 각 테이블의 마지막 처리 id) |

옛 형식(`broker_fill`, `trade_fill`, `broker_balance`, `broker_balance_meta`, `broker_order`)과 옛 커서 행(`fill`, `balance`)은 쓰지 않는다 — 데이터 초기화 때 지운다.

# 명령·조회 HTTP (앱 → 게이트웨이)

- 베이스 `http://gateway:8081`, JSON. 공유 토큰: 헤더 `X-Gateway-Token`(게이트웨이에 토큰이 설정돼 있으면 필수, 틀리면 401). 사설망에서만 바인딩한다.
- 즉발 요청(수동 주문·취소, 주문 흐름)은 헤더 `X-Call-Priority: urgent` — 게이트웨이의 증권사 호출 대기열에서 먼저 나간다.
- `{market}` 은 `KR`. 숫자는 JSON 숫자(소수는 plain 표기).

| 메서드 경로 | 요청 | 응답 |
|---|---|---|
| `POST /api/orders` | `{requestId, market, symbol, side: BUY|SELL, quantity, limitPrice}` | `{orderNo, message, branchNo}` |
| `POST /api/orders/cancel` | `{requestId, market, symbol, orderNo, branchNo, quantity, limitPrice}` | `{ok: true}` |
| `GET /api/orders/{orderNo}/status?market&quantity` | | `{type: FILLED|OPEN|UNKNOWN, price?}` |
| `GET /api/orders/{orderNo}/filled-price?market` | | `{price|null}` |
| `GET /api/orders/fills/today?market` | | `[{orderNo, filledQuantity, averagePrice}]` (증권사가 알려 준 주문별 체결 누적) |
| `GET /api/quotes/{market}/{symbol}` | | `{price, tickSize}` |
| `GET /api/quotes/{market}/{symbol}/previous-close` | | `{price|null}` |
| `GET /api/quotes/{market}/{symbol}/candles?time=HHmmss` | | `[{date, time, open, high, low, close, volume}]` |
| `GET /api/account/holdings?market` | | `[{market, symbol, name, quantity, averagePrice, currentPrice}]` |
| `GET /api/account/buying-power?market&symbol&price` | | `{currency, orderableAmount, maxQuantity}` |
| `GET /api/health` | | `{instanceId, startedAt, contractVersion, kisCredentials, wsConnected, wsConnectedAt, liveSymbols, now}` |

## 멱등
`requestId` 는 앱이 주문·취소마다 새로 만든다. 게이트웨이는 증권사 호출 **전에** `broker_order` 에 SENDING 으로 기록한다. 같은 ID 가 다시 오면 저장된 결과를 돌려주고 또 보내지 않는다. 응답을 못 받았으면 앱은 새 ID 로 재주문하지 말고 같은 ID 로 다시 묻거나 주문 상태를 조회한다.

## 오류 응답 `{error, code}`
| 상태 | code | 뜻 |
|---|---|---|
| 502 | 증권사 `msg_cd` 또는 null | 증권사가 거절·실패 |
| 502 | `PREVIOUS_FAILED` | 같은 요청 ID 가 이미 실패로 기록됨 |
| 409 | `RESULT_UNKNOWN` | 같은 요청 ID 가 "보내는 중"인 채 결과를 모름 — 앱이 주문 상태를 조회해 확정 |
| 422 | `INVALID` | 요청 값 오류 |
| 501 | `UNSUPPORTED` | 이 브로커가 지원하지 않는 기능 |
| 401 | — | 토큰 불일치 |

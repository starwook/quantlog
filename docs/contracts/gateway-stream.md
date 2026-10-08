# 웹소켓 스트림 `/stream` (게이트웨이 → 앱)

- `ws://gateway:8081/stream`, 연결 시 헤더 `X-Gateway-Token`. 앱은 아무것도 보내지 않는다. 모든 메시지는 `{"type": ..., ...}` JSON.
- 앱은 3초마다 연결을 확인해 다시 붙고, 20초 넘게 메시지(ping 포함)가 없으면 끊고 다시 붙는다.

| type | 필드 | 설명 |
|---|---|---|
| `hello` | `instanceId, startedAt, contractVersion` | 연결 직후 한 번 |
| `ping` | `at` | 5초마다 |
| `tick` | `market, symbol, price` | 종목별 최신 값만 50ms 간격(유실 무관) |
| `candle` | `market, symbol, candle{date,time,open,high,low,close,volume}` | 분봉 갱신, 종목별 최신 값만 |
| `fill` | `id` | `broker_notice` 에 새 행(id) — 빠른 알림일 뿐 정본은 DB |
| `balance` | `seq` | `broker_balance_meta.seq` 가 올랐다 — 위와 같음 |

# 웹소켓 스트림 `/stream` (게이트웨이 → 앱)

- `ws://gateway:8081/stream`, 연결 시 헤더 `X-Gateway-Token`. 앱은 아무것도 보내지 않는다. 모든 메시지는 `{"type": ..., ...}` JSON.
- 앱은 3초마다 연결을 확인해 다시 붙고, 20초 넘게 메시지(ping 포함)가 없으면 끊고 다시 붙는다.

| type | 필드 | 설명 |
|---|---|---|
| `hello` | `instanceId, startedAt, contractVersion` | 연결 직후 한 번 |
| `ping` | `at` | 5초마다 |
| `raw` | `data` | 한투 실시간 시세 메시지 원문 한 건(`0|H0STCNT0|001|...`). 해석(틱·분봉 만들기)은 앱이 한투 문서대로. 묶지 않고 모두 보내되, 느린 앱이면 새 시세를 버린다(대기 5000건 초과) |
| `fill` | `id` | `kis_broker_fill` 에 새 행(id) — 빠른 알림일 뿐 정본은 DB |
| `balance` | `seq` | `broker_balance_meta.seq` 가 올랐다 — 위와 같음 |

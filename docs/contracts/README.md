# 게이트웨이 ↔ 앱 계약

게이트웨이(`gateway`)와 앱(`app`)은 코드를 공유하지 않는다. 합의한 것은 아래 형식뿐이다. **추가만 하고 삭제·의미 변경은 하지 않는다**(게이트웨이를 다시 띄울 일을 만들지 않으려고). 계약 버전: **1**(`gateway_instance.contract_version`, 앱의 `APP_CONTRACT_VERSION`).

| 파일 | 내용 | 쓰는 쪽 → 읽는 쪽 |
|---|---|---|
| [gateway-http.md](gateway-http.md) | 명령·조회 HTTP API | 앱 → 게이트웨이 |
| [gateway-stream.md](gateway-stream.md) | 웹소켓 `/stream` 메시지 | 게이트웨이 → 앱 |
| [tables.md](tables.md) | 공유 DB 테이블 형식 | 각 표 참고 |

각 쪽 선언: 게이트웨이 `com.quantlog.gateway.record.*`·`lease.*`·`api.*`, 앱 `com.quantlog.gatewayclient.*`. 형식을 바꿀 때는 양쪽 테스트(`BrokerApiTest`/`GatewayBrokerClientTest`, `GatewayStreamClientTest`)를 같이 고친다.

# TODO

## 해외(미장) 실시간 시세 WebSocket 연동
현재 해외 종목은 실시간 푸시가 없어서 차트 현재가가 20초 REST 폴링으로만 갱신되고, 청산 감시도 1초 폴링이 맡는다.
사양 조사는 끝났다 → [`kis-api/field-reference.md`](kis-api/field-reference.md) 2절 (TR `HDFSCNT0`, 26필드, tr_key `D+거래소+종목`).

**먼저 실측할 것** (집이 아닌 곳에서, 모의투자 키로 — 스모크 스크립트 아이디어: approval_key 발급 → `ws://ops.koreainvestment.com:31000/tryitout/HDFSCNT0` 에 `DNASAAPL`(정규장)·`RBAQAAPL`(주간거래) 구독 → 90초간 수신):
1. 모의(:31000)에서 `HDFSCNT0` 구독이 되는가 — 응답 `rt_cd`/`msg1`. 안 되면 해외 실시간은 실전 계좌 전환(2단계) 이후로 미룬다.
2. 틱이 온다면 지연이 몇 분인가 — 수신 시각 vs 틱의 한국시간. 지연 시세면 청산 감시에는 못 쓰고 화면 표시용으로만 쓴다.
3. 필드가 26개로 오는가, 영문 식별자 없이 순서만 믿어도 되는가.
미국 정규장은 한국 시각 22:30~05:00(서머타임), 주간거래는 10:00~18:00 무렵이라 낮에도 주간 키로 테스트할 수 있다.

**구현 방향** (실측 결과가 괜찮을 때): `RealtimePriceFeed` / `RealtimeSymbolSource` 인터페이스(`broker/RealtimePriceFeed.kt`)의 해외 구현체를 하나 더 만들어 `PriceTick`을 발행한다. 분봉 합성·`ChartBroadcaster` 푸시는 기존 국내 경로를 재사용한다.
- 구독 상한은 세션당 41건이고 **국내·해외·파생 합산**이다. 국내 보유·매매 종목이 이미 쓰고 있어서 우선순위 정책이 필요하다(`RealtimeSymbolSource`가 우선순위 순서로 돌려주고 상한을 넘으면 뒤쪽은 폴링).
- 계좌(appkey)당 WebSocket 1세션이므로 국내 연결과 **같은 연결**에서 해외 TR을 같이 구독해야 한다(공식 샘플에서 혼합 구독 가능 확인).
- 필드 단위·유효기간 등 "확인 못 함" 항목은 실측하고 `field-reference.md`에 `[실측 날짜]`로 표시한다.

## 전일 종가 필드 실측
`BrokerClient.previousClose`가 쓰는 국내 `stck_prdy_clpr`, 해외 `base`를 실제 응답으로 확인한다(공식 예제 근거만 있고 아직 호출해 보지 않았다). 틀리면 차트 등락률이 "시가 대비"로 대신 표시된다.

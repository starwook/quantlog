# 한국투자증권(KIS) Open API 원문·정리

2026-09-25에 공식 GitHub 저장소 `koreainvestment/open-trading-api` (main)에서 필요한 파일만 그대로 저장. **원문은 수정하지 않는다.**
공식 포털: https://apiportal.koreainvestment.com (API 명세는 JS 렌더링이라 직접 저장하지 못함 → 저장소 예제 코드의 docstring이 명세 역할)

## 저장된 파일

| 파일 | 원본 경로 | 설명 |
|---|---|---|
| `README.upstream.md` | `README.md` | 저장소 소개·설정 방법 |
| `llms.txt` | `llms.txt` | AI용 인덱스 |
| `kis_devlp.template.yaml` | `kis_devlp.yaml` | 설정 템플릿 (도메인 주소 포함, **값은 플레이스홀더**) |
| `convention.md` | `docs/convention.md` | 예제 코드 컨벤션 |
| `examples/kis_auth.py` | `examples_llm/kis_auth.py` | 인증·공통 호출 (헤더, 토큰, 웹소켓) |
| `examples/auth/auth_token.py` | `examples_llm/auth/auth_token/` | 토큰 발급 |
| `examples/overseas_stock/*.py` | `examples_llm/overseas_stock/*/` | 해외(미국) 주문·시세·잔고 |
| `examples/domestic_stock/*.py` | `examples_llm/domestic_stock/*/` | 국내 주문·시세·잔고 |

## 코드에 반영한 스펙 (모의투자)

- 모의투자 REST 도메인: `https://openapivts.koreainvestment.com:29443` (실전은 `https://openapi.koreainvestment.com:9443`)
- 토큰: `POST /oauth2/tokenP` `{grant_type: client_credentials, appkey, appsecret}` → `access_token` (24시간)
- 공통 헤더: `authorization: Bearer …`, `appkey`, `appsecret`, `tr_id`, `custtype: P`
- 응답: `rt_cd == "0"` 이 성공, 실패 시 `msg_cd`/`msg1`
- 실전 TR ID 첫 글자(T/J/C)를 `V`로 바꾸면 모의 TR ID (`kis_auth._url_fetch`)

| 기능 | 시장 | 경로 | 모의 TR ID |
|---|---|---|---|
| 현재가 | 미국 | `GET /uapi/overseas-price/v1/quotations/price` (EXCD: NAS/NYS/AMS) | `HHDFS00000300` (실전·모의 공통) |
| 현재가 | 국내 | `GET /uapi/domestic-stock/v1/quotations/inquire-price` | `FHKST01010100` |
| 매수가능 | 미국 | `GET /uapi/overseas-stock/v1/trading/inquire-psamount` | `VTTS3007R` |
| 매수가능 | 국내 | `GET /uapi/domestic-stock/v1/trading/inquire-psbl-order` | `VTTC8908R` |
| 잔고 | 미국 | `GET /uapi/overseas-stock/v1/trading/inquire-balance` | `VTTS3012R` |
| 잔고 | 국내 | `GET /uapi/domestic-stock/v1/trading/inquire-balance` | `VTTC8434R` |
| 매수 | 미국 | `POST /uapi/overseas-stock/v1/trading/order` | `VTTT1002U` |
| 매도 | 미국 | 〃 | `VTTT1001U` ※ |
| 매수/매도 | 국내 | `POST /uapi/domestic-stock/v1/trading/order-cash` | `VTTC0012U` / `VTTC0011U` |
| 정정/취소 | 미국 | `POST /uapi/overseas-stock/v1/trading/order-rvsecncl` | `VTTT1004U` (미구현) |

## 주의 / 확인 필요

- **OCO(동시 청산) 지원 여부를 문서에서 못 찾음**: 익절/손절 두 주문을 동시에 걸어두고 하나 체결되면 나머지가 자동 취소되는 기능이 국내/해외 모의 주문 API에 있는지 확인 안 됨 (토스는 지원 확인됨, `docs/toss-api`). 우회책: 매수 체결 후 별도 프로세스가 가격을 감시하다가 청산 기준에 닿는 순간에만 매도 주문을 낸다 — 두 주문을 동시에 걸어두는 게 아니라서 "동시에 두 개 체결" 문제 자체가 안 생긴다. 2026-09-29 SOXL 매수→매도로 실제 검증됨.

- ※ 미국 매도 모의 TR ID `VTTT1001U`: 포털 원문([portal-notes.md](portal-notes.md))으로 확인됨.
- 모의투자 미국 주문은 **지정가(`ORD_DVSN=00`)만 가능**. 모의는 **일부 종목만 매매 가능** — 종목이 거절되면 다른 종목으로 바꿔 본다.
- **주문 가능 시간(서머타임, 한국시간)**: 프리마켓 17:00~22:30 / 정규장 22:30~05:00 / 애프터마켓 05:00~07:00 — 모두 같은 주문 API로 가능(포털 명시). 그 외 시간은 에러. 서머타임은 2026-11-01 종료(이후 1시간씩 늦어짐).
- **미국 주간거래(10:00~18:00)는 별도 API이며 모의투자 미지원** → 모의 테스트 불가, 구현하지 않음.
- 미국 미체결 조회(`inquire-nccs`)는 예제에 실전 TR(`TTTS3018R`)만 있어 모의 지원 여부 불명 → 미구현.
- 응답 필드명(`last`, `ord_psbl_frcr_amt`, `ovrs_cblc_qty`, `ODNO` 등)은 예제 코드에 명시되지 않아 공식 응답 규격 기억을 바탕으로 매핑했다. 첫 실행은 `application-local.yml`의 `kis.mock.log-raw: true`로 원문을 보고 맞춘다. **실전 확인됨**(2026-09-28, SOXL/AMEX): `last`, `ord_psbl_frcr_amt`, `max_ord_psbl_qty` 필드명이 실제 응답과 일치했다.
- 모의투자 서버 호출 빈도 제한을 **실측 확인**(2026-09-28): 600ms 간격으로 현재가→매수가능→잔고를 연달아 호출하니 잔고 조회에서 `EGW00201 초당 거래건수를 초과하였습니다`가 실제로 발생했다. `ACCOUNT` 그룹(문서상 초당 최대 1회)이 원인으로 보여 기본 호출 간격을 **1100ms**로 올렸다 (`KisProperties.minIntervalMillis`).
- **토큰 발급은 1분당 1회로 제한**된다 (실측, 2026-09-28: `EGW00133 접근토큰 발급 잠시 후 다시 시도하세요(1분당 1회)`). 우리 `SmokeTestRunner`는 실행마다 새 JVM 프로세스라 매번 새로 토큰을 발급받으므로, **연속 실행 시 최소 60초 이상 간격**을 둬야 한다. 장시간 떠 있는 실제 봇은 토큰을 메모리에 캐싱해 24시간 재사용하므로(`KisTokenProvider`) 이 문제가 없지만, 프로세스를 매번 새로 띄우는 지금의 점검용 실행 방식에서는 주의가 필요하다. 토큰을 파일에 캐싱해 프로세스 간 재사용하는 개선은 `[TODO]`.
- **주문 실행이 3회 연속(SOXL 2회, AAPL 1회) `EGW00202 GW라우팅 중 오류가 발생했습니다`로 실패**(2026-09-28~29). 매번 같은 세션에서 시세·매수가능·잔고 **조회는 정상**이었고 주문(쓰기)만 실패했다. **SOXL·AAPL 둘 다 같은 에러**라 특정 종목(서킷브레이커 등) 문제는 아니다.
  - **가장 유력한 원인**: 포털 원문에 "해외주식 서비스 신청 후 이용 가능합니다 (해외증권 거래신청 참고)"라는 문구가 있다 — 앱키 발급·모의투자 신청과 별개로, **계좌에 해외주식 거래신청이 안 돼 있으면** 조회는 되고 주문만 이렇게 막힐 수 있다. `[확인 필요]`: 한투 앱/HTS에서 모의계좌에 해외주식 거래신청이 되어 있는지 확인.
  - 신청이 이미 돼 있는데도 이 에러가 나면 KIS 서버 쪽 문제로 보고 재시도(단, 토큰 발급 1분 제한 때문에 프로세스를 새로 띄우는 지금 방식으론 재시도마다 60초 이상 걸림).
  - `clientOrderId`(멱등키)를 아직 안 보내고 있어, 재시도 시 중복 주문 여부는 KIS 쪽에 별도 확인 필요.
- 해외주식 모의투자는 KIS Developers에서 **모의투자 신청 + 해외주식 모의투자 신청**이 필요할 수 있음 (포털에서 확인).
- hashkey: 예제는 POST에 hashkey를 붙이지만 현행 규격에서는 필수가 아닌 것으로 알고 있어 생략했다. 주문이 거부되면 `/uapi/hashkey` 를 추가한다.

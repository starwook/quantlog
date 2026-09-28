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

- ※ 미국 매도 모의 TR ID `VTTT1001U`: 포털 원문([portal-notes.md](portal-notes.md))으로 확인됨.
- 모의투자 미국 주문은 **지정가(`ORD_DVSN=00`)만 가능**. 모의는 **일부 종목만 매매 가능** — 종목이 거절되면 다른 종목으로 바꿔 본다.
- **주문 가능 시간(서머타임, 한국시간)**: 프리마켓 17:00~22:30 / 정규장 22:30~05:00 / 애프터마켓 05:00~07:00 — 모두 같은 주문 API로 가능(포털 명시). 그 외 시간은 에러. 서머타임은 2026-11-01 종료(이후 1시간씩 늦어짐).
- **미국 주간거래(10:00~18:00)는 별도 API이며 모의투자 미지원** → 모의 테스트 불가, 구현하지 않음.
- 미국 미체결 조회(`inquire-nccs`)는 예제에 실전 TR(`TTTS3018R`)만 있어 모의 지원 여부 불명 → 미구현.
- 응답 필드명(`last`, `ord_psbl_frcr_amt`, `ovrs_cblc_qty`, `ODNO` 등)은 예제 코드에 명시되지 않아 공식 응답 규격 기억을 바탕으로 매핑했다. 첫 실행 시 `KIS_MOCK_LOG_RAW=true` 로 원문을 보고 맞춘다.
- 모의투자 서버는 호출 빈도 제한이 있다(초당 건수는 미확인). 기본 호출 간격을 600ms로 둠.
- 해외주식 모의투자는 KIS Developers에서 **모의투자 신청 + 해외주식 모의투자 신청**이 필요할 수 있음 (포털에서 확인).
- hashkey: 예제는 POST에 hashkey를 붙이지만 현행 규격에서는 필수가 아닌 것으로 알고 있어 생략했다. 주문이 거부되면 `/uapi/hashkey` 를 추가한다.

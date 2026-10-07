# KIS 응답 필드·해외 실시간 WebSocket 사양 (GPT 조사)

- 출처: 2026-10-02 GPT 조사 결과를 옮겨 적음. 근거는 공식 GitHub `koreainvestment/open-trading-api`의 예제 코드·주석과 KIS Developers 공지. **공식 원문 사본이 아니라 정리본이다** (원문 사본은 `examples/`, `README.upstream.md`).
- 검증 상태: 아래 항목은 모두 **실측 전**이다. 실측으로 확인한 항목은 `[실측 YYYY-MM-DD]`를 붙인다. "확인 못 함"은 공식 원문에 없다는 뜻이다.

## 1. REST 현재가 응답의 전일 대비 필드

### 국내 `GET /uapi/domestic-stock/v1/quotations/inquire-price` (FHKST01010100)
출처: `examples_llm/domestic_stock/inquire_price/chk_inquire_price.py`

| 필드 | 의미 |
|---|---|
| `stck_prpr` | 현재가 (코드에서 이미 사용) |
| `stck_prdy_clpr` | 주식 전일 종가 |
| `stck_sdpr` | 주식 기준가 (**전일 종가와 별개 필드**) |
| `prdy_vrss` | 전일 대비 |
| `prdy_vrss_sign` | 전일 대비 부호 |
| `prdy_ctrt` | 전일 대비율(%) |
| `aspr_unit` | 호가 단위 (코드에서 이미 사용, 실측됨) |

### 해외 `GET /uapi/overseas-price/v1/quotations/price` (HHDFS00000300, 실전·모의 TR ID 공통)
출처: `examples_llm/overseas_stock/price/chk_price.py`, `price.py`

| 필드 | 의미 |
|---|---|
| `last` | 현재가 |
| `base` | 전일종가 |
| `diff` | 대비 |
| `rate` | 등락율 |
| `sign` | 대비기호 |
| `pvol` / `tvol` / `tamt` | 전일거래량 / 거래량 / 거래대금 |
| `rsym` | 실시간조회종목코드 |
| `zdiv` | 소수점자리수 |

코드 반영: `BrokerClient.previousClose` — 국내 `stck_prdy_clpr`, 해외 `base` (차트 전일 대비 등락률). 실측 전.

## 2. 해외주식 실시간 WebSocket (HDFSCNT0)

출처: `legacy/websocket/python/ws_domestic+overseas_stock.py`, `ws_domestic_overseas_all.py`, `examples_llm/kis_auth.py`, KIS 공지(2026-04-20, 유량)

- TR ID `HDFSCNT0` (공식 코드는 "해외주식 실시간지연체결가"라고 부른다). 실전 `ws://ops.koreainvestment.com:21000`, 모의 `ws://ops.koreainvestment.com:31000`.
- **확인 못 함**: 모의(:31000)에서 HDFSCNT0가 실제로 지원되는지(공식 예제는 실전 URL로 HDFSCNT0 구독), 미국 무료 체결의 정확한 지연 시간. 미국은 유료 시세 제공 없음(`asking_price.py` 주석). 다른 시장의 무료/유료 구분은 HTS/MTS 신청 필요.
- 구독 요청: header `{approval_key, custtype: "P", tr_type: "1"(등록)/"0"(해제), content-type: "utf-8"}`, body `{input: {tr_id: "HDFSCNT0", tr_key: "DNASAAPL"}}`.
- tr_key = `D` + 거래소(`NAS`/`NYS`/`AMS`) + 종목코드 (정규장). 주간거래는 `R` + `BAQ`(나스닥)/`BAY`(뉴욕)/`BAA`(아멕스) + 종목코드 (예: `RBAQAAPL`, 공식 샘플에서 확인). `DNYS…`·`DAMS…`·`RBAY…` 조합을 공식 WebSocket 샘플이 직접 쓰는지는 확인 못 함.
- 수신: `0|HDFSCNT0|데이터개수|필드^필드^…` (`|`로 나눈 뒤 4번째를 `^`로 분리). PINGPONG은 받은 payload 그대로 `pong` 전송.
- 필드 26개 (순서, 한국어 의미. 영문 식별자는 공식 코드가 같이 주지 않아 순서+의미만 신뢰한다. 단위는 확인 못 함):
  1 실시간종목코드 · 2 종목코드 · 3 소수점자리수 · 4 현지영업일자 · 5 현지일자 · 6 현지시간 · 7 한국일자 · 8 한국시간 · 9 시가 · 10 고가 · 11 저가 · 12 현재가 · 13 대비구분 · 14 전일대비 · 15 등락율 · 16 매수호가 · 17 매도호가 · 18 매수잔량 · 19 매도잔량 · 20 체결량 · 21 거래량 · 22 거래대금 · 23 매도체결량 · 24 매수체결량 · 25 체결강도 · 26 시장구분
- 구독 상한: **세션당 41건, 국내·해외·파생 합산** (공지 2026-04-20). 계좌(appkey)당 1세션. 한 연결에서 국내·해외 TR 혼합 구독 가능(공식 샘플).
- approval_key: `POST {도메인}/oauth2/Approval` body `{grant_type: client_credentials, appkey, secretkey}` → `approval_key`. 공식 샘플은 24시간마다 재발급하지만 서버가 문서화한 유효기간은 확인 못 함.

## 3. 실시간 체결통보 WebSocket (국내 H0STCNI0/9, 해외 H0GSCNI0/9)

출처: 공식 샘플 `examples_llm/domestic_stock/ccnl_notice`, `examples_llm/overseas_stock/ccnl_notice`, `examples_llm/kis_auth.py`, `examples_user/domestic_stock/domestic_stock_examples_ws.py` (웹 요약 경유 — **모의 실측 전**). 구현: `broker/kis/KisFillNoticeHandler.kt`.

- TR ID: 국내 실전 `H0STCNI0` / 모의 `H0STCNI9`, 해외 실전 `H0GSCNI0` / 모의 `H0GSCNI9`. 시세와 같은 연결(키당 1세션)에서 같이 구독한다.
- 구독: 시세와 같은 요청 형식. 국내 `tr_key` 는 **HTS ID**(샘플: `kws.subscribe(request=ccnl_notice, data=[trenv.my_htsid])`). 해외 `tr_key` 는 샘플 요약에 "종목코드"라고 나왔으나 불확실 — 이 앱은 HTS ID 로 구독한다(**실측 필요**). 체결통보는 구독 한도(41건)에서 건당 1개를 쓴다.
- 암호화: 구독 응답 JSON 의 `body.output.key` / `iv` 를 저장하고, 수신 데이터(`0|1|TR_ID|건수|데이터` 의 4번째)를 Base64 디코딩 → AES-256-CBC(키·IV 는 UTF-8 문자열) → PKCS7 패딩 제거. 평문 메시지는 맨 앞 글자가 "0".
- 국내 필드 26개(순서): CUST_ID, ACNT_NO, ODER_NO, OODER_NO, SELN_BYOV_CLS, RCTF_CLS, ODER_KIND, ODER_COND, STCK_SHRN_ISCD, CNTG_QTY, CNTG_UNPR, STCK_CNTG_HOUR, RFUS_YN, CNTG_YN, ACPT_YN, BRNC_NO, ODER_QTY, ACNT_NAME, ORD_COND_PRC, ORD_EXG_GB, POPUP_YN, FILLER, CRDT_CLS, CRDT_LOAN_DATE, CNTG_ISNM40, ODER_PRC
- 해외 필드 25개(순서): CUST_ID, ACNT_NO, ODER_NO, OODER_NO, SELN_BYOV_CLS, RCTF_CLS, ODER_KIND2, STCK_SHRN_ISCD, CNTG_QTY, CNTG_UNPR, STCK_CNTG_HOUR, RFUS_YN, CNTG_YN, ACPT_YN, BRNC_NO, ODER_QTY, ACNT_NAME, CNTG_ISNM, ODER_COND, DEBT_GB, DEBT_DATE, START_TM, END_TM, TM_DIV_TP, CNTG_UNPR12
- `CNTG_YN`(국내): `2` 체결통보, `1` 주문·정정·취소·거부 접수 통보. 해외는 값 정의를 못 찾음.
- **[실측 2026-10-07, 모의 :31000, 삼성전자 1주 수동 매수·매도]**
  - 체결통보가 **실제로 온다.** 주문 API 호출 후 약 1~1.5초에 도착했고, 우리 주문 응답 로그(`[수동 주문]`)보다 7초 이상 빨랐다(그 사이 호출 한도 재시도·대기). 즉 REST 체결 조회보다 훨씬 빠르다.
  - **주문 한 건당 통보가 2건** 3ms~43ms 간격으로 연달아 왔다(접수 + 체결로 추정, 구분 확인은 아래 진단 로그 뒤).
  - **한 건이 26개가 아니라 23개 필드**로 왔다(문서의 26은 맞지 않음). 앞쪽 필드 위치는 샘플과 같은지 `[체결통보 진단]` 로그로 확인 중 — 확정 전까지 앞 17개(주문번호~주문수량)만 읽는다. 건당 필드 수는 메시지 헤더의 건수로 나눠 구한다.
- **확인 못 함**: 모의(:31000)에서 실제 수신되는지 · 해외 `tr_key` · 구독 해제 `tr_type`(문서마다 `0`/`2`, 기존 시세 코드는 `2`) · 부분체결이 건별/누적 중 무엇인지 · `SELN_BYOV_CLS` 값(매도/매수 코드) · 해외 접수/체결 구분 값. 앱 로그의 `[체결통보]` 줄로 실측해서 이 문서를 고친다.

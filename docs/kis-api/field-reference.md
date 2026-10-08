# KIS 응답 필드·실시간 체결통보 사양 (GPT 조사)

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

코드 반영: `BrokerClient.previousClose` — 국내 `stck_prdy_clpr` (차트 전일 대비 등락률). 실측 전.

## 2. 실시간 체결통보 WebSocket (H0STCNI0/9)

출처: 공식 샘플 `examples_llm/domestic_stock/ccnl_notice`, `examples_llm/kis_auth.py`, `examples_user/domestic_stock/domestic_stock_examples_ws.py` (웹 요약 경유 — **모의 실측 전**). 구현: `broker/kis/KisFillNoticeHandler.kt`.

- TR ID: 실전 `H0STCNI0` / 모의 `H0STCNI9`. 시세와 같은 연결(키당 1세션)에서 같이 구독한다.
- 구독: 시세와 같은 요청 형식. 국내 `tr_key` 는 **HTS ID**(샘플: `kws.subscribe(request=ccnl_notice, data=[trenv.my_htsid])`). 체결통보는 구독 한도(41건)에서 건당 1개를 쓴다.
- 암호화: 구독 응답 JSON 의 `body.output.key` / `iv` 를 저장하고, 수신 데이터(`0|1|TR_ID|건수|데이터` 의 4번째)를 Base64 디코딩 → AES-256-CBC(키·IV 는 UTF-8 문자열) → PKCS7 패딩 제거. 평문 메시지는 맨 앞 글자가 "0".
- 국내 필드 26개(순서): CUST_ID, ACNT_NO, ODER_NO, OODER_NO, SELN_BYOV_CLS, RCTF_CLS, ODER_KIND, ODER_COND, STCK_SHRN_ISCD, CNTG_QTY, CNTG_UNPR, STCK_CNTG_HOUR, RFUS_YN, CNTG_YN, ACPT_YN, BRNC_NO, ODER_QTY, ACNT_NAME, ORD_COND_PRC, ORD_EXG_GB, POPUP_YN, FILLER, CRDT_CLS, CRDT_LOAN_DATE, CNTG_ISNM40, ODER_PRC
- `CNTG_YN`(국내): `2` 체결통보, `1` 주문·정정·취소·거부 접수 통보.
- **[실측 2026-10-07, 모의 :31000, 서버에서 삼성전자 1주 수동 매수·매도 2회]** — 국내 체결통보는 모의에서 **실제로 온다.**
  - **도착 시간:** 주문 API 호출 후 약 1~2초(매수 09:23:42 → 통보 09:23:44, 매도 09:24:15 → 09:24:16). 같은 주문의 REST 확인 로그(`[수동 주문]`)는 8~12초 뒤에 찍혔다(잔고·체결 REST 가 3~4초씩 걸림) — 통보가 REST 보다 훨씬 빠르다.
  - **주문 한 건당 통보 2건**이 3~10ms 간격으로 온다: 접수 통보(`CNTG_YN=1`, `ACPT_YN=1`) → 체결 통보(`CNTG_YN=2`, `ACPT_YN=2`). 모의는 지정가보다 유리하게 체결될 수 있다(매수 지정가 278,500 → 체결 277,500).
  - **한 건이 26개가 아니라 23개 필드**다(문서의 26은 맞지 않음). 위치(0부터): 0 고객ID · 1 계좌번호 · 2 `ODER_NO` 주문번호 · 3 `OODER_NO` 원주문번호(신규는 빈 값) · 4 `SELN_BYOV_CLS` **`01`=매도 / `02`=매수** · 5 `RCTF_CLS`(0) · 6 `ODER_KIND`(00) · 7 `ODER_COND`(0) · 8 종목코드 · 9 수량 · 10 단가 · 11 시각 HHmmss · 12 `RFUS_YN` 거부(0=정상) · 13 `CNTG_YN` · 14 `ACPT_YN` · 15 `BRNC_NO` · 16 `ODER_QTY` 주문수량 · 17 계좌명 · 18 `1Y`(의미 모름) · 19 `10`(의미 모름) · 20 빈 값 · 21 종목명 · 22 주문가(체결 통보에서만, 접수 통보는 빈 값).
  - **9·10(수량·단가)의 의미는 통보 종류에 따라 다르다.** 접수 통보에서는 주문 수량·주문가이고, 체결 통보에서만 체결 수량·체결단가다. 체결로 취급하려면 `CNTG_YN=2` 인지 먼저 본다(`FillNotice.isFill`).
  - 건당 필드 수는 메시지 헤더의 건수로 나눠 구한다(코드: 앞 17개만 읽어서 뒤쪽 필드 변경에 영향 없음).
- **아직 확인 못 함**: 부분체결이 건별/누적 중 무엇인지(1주 주문이라 못 봄 — 수량 2주 이상 주문이 나눠 체결될 때 확인) · 취소·정정·거부 통보의 값 · 18·19번 필드 의미 · 구독 해제 `tr_type`(문서마다 `0`/`2`, 기존 시세 코드는 `2`).

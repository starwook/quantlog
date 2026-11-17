# 해외선물(나스닥100 선물) 시세 — 한투는 유료라 야후를 쓴다

2026-11-17 조사·실측. 결론: **한투 해외선물 시세는 CME 유료 시세를 신청해야 나와서 쓰지 않고, `/futures/nasdaq` 화면은 야후 파이낸스(무료, 약 10분 지연)로 그린다.**

## 한투 해외선물옵션 REST (쓰지 않음, 기록용)
출처: 공식 저장소 `koreainvestment/open-trading-api` 의 `examples_llm/overseas_futureoption/`.

| 용도 | 경로 (`/uapi/overseas-futureoption/v1/quotations/` 뒤) | TR ID | 주요 파라미터 |
|---|---|---|---|
| 현재가 | `inquire-price` | `HHDFC55010000` | `SRS_CD`(종목코드) |
| 분봉 | `inquire-time-futurechartprice` | `HHDFC55020400` | `SRS_CD`, `EXCH_CD`(CME 등), `START_DATE_TIME`(빈 값), `CLOSE_DATE_TIME`(YYYYMMDD), `QRY_TP`(Q 첫 조회/P 연속), `QRY_CNT`(예 120), `QRY_GAP`(분 간격 1·5·10·15·30·60), `INDEX_KEY`(연속조회 키) |
| 일봉 | `daily-ccnl` | `HHDFC55020100` | 분봉과 같은 이름, `QRY_CNT` 최대 40, `QRY_GAP` 빈 값 |

- 분봉 응답 필드(예제의 이름표): `data_date` 일자 · `data_time` 시각 · `open_price`/`high_price`/`low_price`/`last_price` 시·고·저·체결가 · `last_qntt` 체결수량 · `vol` 누적거래수량 · `index_key` 이전조회KEY. 연속조회는 응답 헤더 `tr_cont` 가 M·F 일 때.
- 종목코드는 상품코드 + 월 문자 + 연도 두 자리로 보인다(예제 값 `CNHU24`, `6AM24`, `BONU25`). 나스닥100 선물 2026년 12월물은 `NQZ26` 으로 넣었다 — 아래 실측은 권한 오류로 끝나 **코드가 맞는지는 확인되지 않았다.**

### 실측 (2026-11-17 09:20 KST, 서버 게이트웨이 `/api/kis/...` 통로, 읽기 전용 2회)
- 현재가(`SRS_CD=NQZ26`)·분봉(`SRS_CD=NQZ26&EXCH_CD=CME&CLOSE_DATE_TIME=20261117&QRY_TP=Q&QRY_CNT=120&QRY_GAP=1`) 모두 HTTP 500, `rt_cd` 1, **`msg_cd` `EGW00550`, `msg1` "CME SUB거래소 신청 계좌가 아닙니다."**
- 요청에 계좌번호는 들어가지 않지만 한투가 계정의 CME 시세 신청 여부를 본다. 주문이 아닌 조회도 막힌다.
- 서버 게이트웨이는 모의 도메인으로 보인다(설정 기본값에서 추정). **실전 도메인·실전 키에서의 결과는 미실측.**
- 이 오류 본문은 첫 키에 따옴표가 없다(`{rt_cd:"1",...}`) — 엄격한 JSON 파서는 못 읽는다.

### 비용 (한투 공지, 2026-01-01 적용)
- CME 거래소 API 시세: SUB거래소(CME·CBOT·NYMEX·COMEX)마다 **월 228.8 USD**(VAT 포함). 나스닥100 선물(NQ·MNQ)은 SUB거래소 `CME`.
- 일반 HTS/MTS 이용자는 무료로 시세를 받는다(공지 문구). API 만 유료다.
- 신청은 HTS `[7936] 해외선물옵션 실시간 시세신청/조회`.

## 야후 파이낸스 (사용 중)
앱 `futures/NasdaqFuturesClient.kt` 가 직접 부른다(게이트웨이와 무관, 키 없음).

- 주소: `GET https://query1.finance.yahoo.com/v8/finance/chart/NQ=F?interval=1m&range=2d` (일봉은 `interval=1d&range=6mo`). 야후가 문서로 공개한 API 가 아니라 야후 웹사이트가 쓰는 통로다 — 예고 없이 막히거나 형식이 바뀔 수 있고 호출 한도도 공개돼 있지 않다. 그래서 앱은 1분봉 60초·일봉 1시간 동안 받은 값을 다시 쓴다.
- 응답: `chart.result[0]` 의 `timestamp[]`(epoch 초) + `indicators.quote[0].open/high/low/close/volume[]`(같은 순서, 과거 → 최근). `meta.previousClose`(1분봉에만 있음)·`meta.regularMarketPrice`. 오류는 `chart.result` 가 null 이고 `chart.error.description` 에 사유.
- 실측(2026-11-17 09:22·09:40 KST): `range=2d` 로 1분봉 약 1,500개(어제 08:10 ~ 지금, 한국 시간 밤·새벽 구간 포함), 마지막 봉이 호출 시각보다 **약 10분 전**. `previousClose` 는 `range=1d`·`2d` 에서 같은 값(전일 종가). 값이 null 인 봉이 섞여 온다(1분봉 60개, 일봉 1개) — 건너뛴다. 일봉에는 `previousClose` 가 없어 직전 봉 종가를 기준으로 쓴다.
- **미확인: 야후 시세를 공개 웹 화면에 내보내도 되는지(약관).** 웹 공개 전에 확인한다(기획서 미결 사항).

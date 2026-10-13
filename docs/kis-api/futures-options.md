# 한투 국내선물옵션 시세 API 조사 (2026-10-13)

출처: 공식 저장소 `koreainvestment/open-trading-api` main `examples_llm/domestic_futureoption/` (43개 폴더). **예제 코드에 응답 필드명이 없다** — 포털 명세는 JS 렌더링이라 저장 못 함. 응답 필드명은 실측 후 이 문서에 채운다. 요청 파라미터·TR ID 는 예제 원문 기준.

## 조회용 REST (게이트웨이 `/api/kis/uapi/...` 통로로 그대로 호출 가능 — 계좌번호 불필요, 게이트웨이 수정 불필요)

| 용도 | 경로 (`/uapi/domestic-futureoption/v1/quotations/...`) | TR ID | 요청 파라미터 |
|---|---|---|---|
| 선물옵션 현재가 | `inquire-price` | `FHMIF10000000` (실전·모의 동일) | `FID_COND_MRKT_DIV_CODE` F(지수선물)/O(지수옵션), `FID_INPUT_ISCD`(예 `101W09`). 응답 output1·2·3 |
| 선물옵션 호가 | `inquire-asking-price` | `FHMIF10010000` (블로그 출처, 원문 미확인) | 위와 동일 |
| 전광판 선물 | `display-board-futures` | `FHPIF05030200` | `FID_COND_MRKT_DIV_CODE=F`, `FID_COND_SCR_DIV_CODE=20503`, `FID_COND_MRKT_CLS_CODE=MKI`(예제값). HTS [0503] 하단 |
| 전광판 콜/풋 (옵션 행사가별) | `display-board-callput` | `FHPIF05030100` | `FID_COND_MRKT_DIV_CODE=O`, `FID_COND_SCR_DIV_CODE=20503`, `FID_MRKT_CLS_CODE=CO`, `FID_MTRT_CNT`(만기 YYYYMM), `FID_MRKT_CLS_CODE1=PO`, `FID_COND_MRKT_CLS_CODE`(선택). output1·output2 각 최대 100건, 호출이 느려 **초당 1회 이하 권장** |
| 전광판 옵션 월물 목록 | `display-board-option-list` | (미확인) | 만기월 목록용 |
| 분봉 | `inquire-time-fuopchartprice` | `FHKIF03020200` | `FID_COND_MRKT_DIV_CODE`, `FID_INPUT_ISCD`, `FID_HOUR_CLS_CODE`(30=30초, 60=1분), `FID_PW_DATA_INCU_YN`(Y 과거/N 당일), `FID_FAKE_TICK_INCU_YN`, `FID_INPUT_DATE_1`, `FID_INPUT_HOUR_1`. 실전 최대 102건, 이어받기는 날짜·시각 지정 |
| 일봉/주봉 | `inquire-daily-fuopchartprice` | `FHKIF03020100` | `FID_COND_MRKT_DIV_CODE`, `FID_INPUT_ISCD`, `FID_INPUT_DATE_1/2`(시작/끝), `FID_PERIOD_DIV_CODE`(D/W). 최대 100건 |

## 야간선물
- 예제 코드 REST 에는 야간 구분 파라미터가 **없다**(분봉·현재가 모두). 야간 시세 전용은 **실시간 웹소켓** 쪽에 있다: `krx_ngt_futures_ccnl` TR `H0MFCNT0`(체결, 구독키 = 선물 종목코드), `krx_ngt_futures_asking_price`(호가), `krx_ngt_option_ccnl`·`_asking_price`·`_exp_ccnl`(야간옵션), 체결통보 `krx_ngt_futures_ccnl_notice`.
- `H0MFCNT0` 출력 50칸 순서: `futs_shrn_iscd, bsop_hour, futs_prdy_vrss, prdy_vrss_sign, futs_prdy_ctrt, futs_prpr, futs_oprc, futs_hgpr, futs_lwpr, last_cnqn, acml_vol, acml_tr_pbmn, hts_thpr, mrkt_basis, dprt, nmsc_fctn_stpl_prc, fmsc_fctn_stpl_prc, spead_prc, hts_otst_stpl_qty, otst_stpl_qty_icdc, oprc_hour, …(시고저 시각·대비), shnu_rate, cttr, esdg, otst_stpl_rgbf_qty_icdc, thpr_basis, futs_askp1, futs_bidp1, askp_rsqn1, bidp_rsqn1, seln/shnu/ntby_cntg_csnu, …, total_askp_rsqn, total_bidp_rsqn, prdy_vol_vrss_acml_vol_rate, dynm_mxpr, dynm_llam, dynm_prc_limt_yn` (전체 순서는 원문 `krx_ngt_futures_ccnl.py`).
- 야간 계좌 쪽 REST: `inquire-ngt-balance`, `inquire-ngt-ccnl`, `inquire-psbl-ngt-order`, `ngt-margin-detail` (주문용이라 이번 범위 아님).

## 옵션 매물대
- 한투에 "매물대" 전용 API 는 없다. **행사가별 콜/풋 미결제약정·거래량**(`display-board-callput` 응답)을 받아 앱이 행사가별로 합산·정렬해 계산한다(응답 필드명은 실측 필요).

## 열린 질문 (실측으로 확인)
1. 응답 필드명 전부(미결제약정, 거래량, 행사가, 현재가 등).
2. 모의투자 도메인에서 이 TR 들이 되는지(예제는 `inquire-price`·일봉만 모의 TR 이 같다고 적음). 안 되면 실전 키로 시세 조회만.
3. 선물옵션 계좌 개설 없이 시세만 조회되는지.
4. 야간 시세를 REST 로 받는 방법(야간 시간대에 `inquire-price` 가 야간 값을 주는지).

## 응답 필드명 (공식 저장소 `chk_*.py` 의 COLUMN_MAPPING 원문, 2026-10-13 — 실측 전, output1/2 구분은 미확인)

**`display-board-callput`** (콜·풋 공통 키): `acpr` 행사가 · `optn_shrn_iscd` 옵션 단축코드 · `optn_prpr` 현재가 · `optn_prdy_vrss`/`optn_prdy_ctrt` 전일대비/율 · `prdy_vrss_sign` · `optn_bidp`/`optn_askp` 매수/매도호가 · `acml_vol` 누적거래량 · `acml_tr_pbmn` 누적거래대금 · **`hts_otst_stpl_qty` 미결제약정 수량 · `otst_stpl_qty_icdc` 미결제약정 증감** · `delta_val` `gama` `vega` `theta` `rho` · `hts_ints_vltl` 내재변동성 · `tmvl_val` 시간가치 · `invl_val` 내재가치 · `atm_cls_name` ATM 구분 · `optn_oprc/hgpr/lwpr/mxpr/llam` · `seln_rsqn`/`shnu_rsqn` 잔량 · `total_askp_rsqn`/`total_bidp_rsqn` · `futs_antc_cnpr` 선물예상체결가 · `unch_prpr` 환산 현재가 · `nmix_sdpr` 지수 기준가.

**`inquire-price`**: `hts_kor_isnm` 종목명 · `futs_prpr` 현재가 · `futs_prdy_vrss`/`futs_prdy_ctrt` · `futs_prdy_clpr` 전일종가 · `futs_oprc/hgpr/lwpr/mxpr/llam` · `acml_vol` · `acml_tr_pbmn` · `hts_otst_stpl_qty` 미결제약정 · `otst_stpl_qty_icdc` 증감 · `basis`·`mrkt_basis` 베이시스 · `hts_thpr` 이론가 · `dprt` 괴리율 · `futs_last_tr_date` 최종거래일 · `hts_rmnn_dynu` 잔존일수 · `bstp_nmix_prpr` 업종지수(KOSPI200) 현재가 외.

## 구현 상태 (2026-10-13)
- 앱 `futures/` 패키지, 화면 `/futures`(메뉴 "선물옵션"). 게이트웨이 수정 없음(`/api/kis/uapi/...` 통로 그대로).
- `FuturesOptionsClient`: `display-board-callput`(콜·풋 행사가별) → `OptionBoard`(콜/풋 최대 미결제약정 행사가, 맥스페인, 풋/콜 비율), `display-board-futures` → 필드를 해석하지 않고 그대로 표로 보여줌.
- **가정(실측 전)**: `output1` = 콜, `output2` = 풋. 실측해서 반대면 `OptionBoard.parse` 한 줄만 바꾼다. 선물 전광판 열은 실측 후 필요한 것만 남긴다.
- 옵션 월물 기본값은 오늘 기준 가장 가까운 월물(만기 = 둘째 목요일, 휴장 보정 없음). 주간옵션은 다루지 않는다.
- 아직 안 한 것: 야간선물(REST 로 야간값이 나오는지 실측 필요), 선물 종목코드를 받는 현재가(`inquire-price`), 일별 매물대 저장.

## 실측 결과 (2026-10-13 14:45, 모의투자 게이트웨이, 장 마감 후)
- **모의 도메인에서 선물옵션 시세 TR 이 된다**(`FHPIF05030100`·`FHPIF05030200`, 계좌 없이 조회). 필드명은 위 `chk_*.py` 매핑과 일치.
- **`display-board-callput`: `output1` = 콜(코드 `B01…C..`), `output2` = 풋(`C01…`) 확인.** 월물 `FID_MTRT_CNT` 는 만기일이 지나면 그 달이 **빈 응답**이다(10/8 만기 후 `202610` → 빈 배열, `202611` 정상) → `OptionMaturity.nearest` 가 맞다.
- **⚠ 한 번에 100행만 오고 연속조회(`tr_cont: N`)·`FID_COND_MRKT_CLS_CODE`(W/M/A) 로도 다음 구간이 안 온다.** 응답은 가장 높은 행사가부터 내려가(예: 1595 → 1347.5) 지수 근처(ATM, 풋 1595 가격으로 역산하면 약 1009)에 닿기 전에 끊긴다 → **ATM 근처 매물대가 빠진다.** 모의 서버 특성인지 실전도 같은지는 미확인. 해결안 후보: 행사가별로 `inquire-price`(옵션 코드, `FID_COND_MRKT_DIV_CODE=O`)를 따로 부른다(호출 수십 번).
- `display-board-futures`: `FID_COND_MRKT_CLS_CODE=MKI` 는 **미니선물**(`A05611` 등). 코스피200 선물은 `""` 또는 `K2I`(`A01612` = `F 202612` 등 7개 월물). 필드: `futs_shrn_iscd, hts_kor_isnm, futs_prpr, futs_prdy_vrss, prdy_vrss_sign, futs_prdy_ctrt, hts_thpr, acml_vol, futs_askp, futs_bidp, hts_otst_stpl_qty(미결제), futs_hgpr, futs_lwpr, hts_rmnn_dynu, total_askp_rsqn, total_bidp_rsqn, futs_antc_cnpr…`. 앱은 `K2I` 를 쓴다.
- 모의 시세의 값이 실제 시장과 같은지(선물 ≈ 942 vs 옵션 행사가 1347~1595)는 확인 못 했다 — 모의 데이터가 이상할 수 있다.

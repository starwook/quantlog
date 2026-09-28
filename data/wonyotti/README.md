# 워뇨띠(aoa) BitMEX 공개 거래내역

- 원본 출처: 사용자 제공 (`~/Downloads/aoa_public_2021-12-31_with_letter/`), 본인이 공개한 거래내역 자료. **개인 연구용으로만 사용하고 재배포·판매하지 않는다** (작성자가 같은 폴더의 서한에서 이 자료로 만든 봇의 판매를 우려한다고 밝힘).
- `raw/` — 원본 CSV 5개 (수정 금지, 약 600MB)
  - `aoa-execution-*.csv` 체결내역 (2018-03 ~ 2021-12, 총 1,444,583행, 41컬럼)
  - `aoa-wallet-2018-03-01-2021-12-31.csv` 지갑 입출금/실현손익
- `derived/` — `analysis/wonyotti/analyze.py`가 만든 산출물 (삭제 후 재생성 가능)
  - `closing_orders.csv` 청산 주문 단위 수익률, `return_hist.csv` 수익률 히스토그램, `wallet_daily.csv` 일별 수익률, `summary.json` 요약
- 서한(`90일 서한.txt`)은 복사하지 않았다 (요청 대상 아님).
- 분석 결과: [docs/워뇨띠-분석.md](../../docs/워뇨띠-분석.md)

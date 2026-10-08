# quantlog

AI 자동매매 봇 + 판단 과정·수익률 공개 웹서비스. 기획은 [docs/기획서.md](docs/기획서.md), 웹 화면은 [docs/웹-화면-기획.md](docs/웹-화면-기획.md).

## 지금 되는 것

한투(KIS) **모의투자** 연동 + 매매 기록 DB/웹. 국내(KR) 주식 전용(해외 주식은 2026-10-08 제거). 실행하면 `http://localhost:8080`에서 계좌 요약·실현손익·매매 기록을 볼 수 있다 (Thymeleaf, 서버는 계속 떠 있음). 보유 종목은 KIS 잔고를 10초마다(체결이 확인되면 즉시 한 번 더) DB(`account_holding`)에 그대로 동기화(`HoldingSyncScheduler`)해 두고 화면은 그 테이블을 읽는다(화면 조회에 KIS 호출 없음, 증권사 앱에서 직접 거래한 물량 포함).

| 기능 | 위치 |
|---|---|
| 브로커 추상화 | `broker/BrokerClient.kt` |
| KIS 모의투자 구현 (시세·매수가능·잔고·지정가 주문) | `gateway/` 모듈의 `kis/` |
| 모킹 체결 (선택, `QUANTLOG_BROKER_TYPE=paper`로 켬 — 기본값은 KIS 모의 주문: 시세는 KIS, 주문은 로컬에서 호가 ±1틱에 즉시 체결, 증권사 수수료 0·제세금(국내 주식 거래세, 국내 ETF 면제)만 반영, `paper_order` 테이블) | `broker/paper/` |
| 청산 판정 (익절 기본 +0.5%·손절 기본 보류, 종목별 DB 값, 목표가는 가장 가까운 호가로 맞춤, 설정으로 조정) | `strategy/ExitRule.kt` |
| 삼성전자 마틴게일 (평단 -0.5%마다 보유 2배로 추가 매수·최대 5단계,손절은 종목 손절 % 하나, 사이클은 매매 기록에서 계산). 국내는 실시간 틱마다 판정하고 체결이 DB에 반영될 때까지 다음 단계를 미루며 10초 미체결이면 취소, 실시간 끊김은 `EntryScheduler` 1초 폴링 | `strategy/MartingaleRule.kt`, `trading/MartingaleService.kt`(틱 판정), `MartingaleTickListener.kt`, `MartingaleScheduler.kt`(미체결 취소) |
| 주문 전 리스크 가드 (주문금액·수량 상한) | `trading/RiskGuard.kt` |
| 연동 점검 실행기 (READ / BUY / SELL / CANDLES) | `trading/SmokeTestRunner.kt` |
| 청산 감시 (국내: 실시간 WebSocket 틱마다(구독 종목은 DB의 보유 종목에서 자동, 상한 `realtime-max-subscriptions`) 그 종목만 판정 / 실시간 끊김: 1초 폴링 → 잔고 사본 DB(체결통보로 즉시 갱신, KIS 잔고 10초 주기 보정) 기준 익절/손절 목표가에 닿으면 한 호가 낮게 전량 매도, `quantlog.exit.enabled`). | `trading/ExitService.kt`(판정·매도), `ExitScheduler.kt`(폴링), `ExitTickListener.kt`(틱) |
| Oracle Cloud 배포 (로컬 수동 `deploy/deploy.sh` / GitHub push 자동 `.github/workflows/deploy.yml`) | `deploy/` (절차는 `deploy/DEPLOY.md`) |
| 국내 분봉 수집 (KIS 당일·최근 30건 → `minute_candle` 테이블에 누적) | `marketdata/` |
| 매매 기록 저장·조회, 체결 원장(게이트웨이 `broker_notice`) 반영·실현손익 계산(체결 직전 평단 기준) | `position/` (MySQL, DB명 `quantlog`, 로컬 root/무비밀번호) |
| 웹 대시보드 (8080) | `position/TradeController.kt` + `resources/templates/trades.html` |

**아직 없는 것**: 자동 매수(진입 판단 규칙 미정), 타임스톱·체결 확인·휴장일 판단, 진입 판단(AI 필요 여부 재검토 중, `docs/기획서.md` 7장), 일일 손실 한도(킬스위치) 코드.

## 오늘 모의투자 테스트하기

### 0. 준비 (한 번만)
1. KIS Developers → 모의투자 신청 → **모의투자 앱키/시크릿**, **모의계좌번호** 확인.
2. 로컬 MySQL이 떠 있어야 한다. DB가 없으면 만든다: `mysql -uroot -e "CREATE DATABASE IF NOT EXISTS quantlog;"` (접속 정보는 `application.yml`의 `spring.datasource`, root/무비밀번호/localhost).
3. 프로젝트 루트에서 템플릿을 복사한다.

   ```bash
   cp application-local.yml.example application-local.yml
   ```

4. `application-local.yml`을 열어 앱키·시크릿·계좌번호를 채운다. **이 파일은 `.gitignore`에 있어 git에 올라가지 않는다.** IntelliJ Run Configuration의 환경변수는 건드릴 필요 없다 — 앱이 이 파일을 자동으로 읽는다.

   ```yaml
   kis:
     mock:
       app-key: "여기에 모의투자 앱키"
       app-secret: "여기에 모의투자 앱시크릿"
       account: "12345678-01"
       hts-id: "KIS Developers 고객(HTS) ID"   # 실시간 체결통보 구독용(선택). 비우면 체결통보만 꺼진다
       log-raw: true
   quantlog:
     smoke:
       mode: "READ"       # READ → BUY → SELL 순서로 바꿔 가며 저장하고 재실행
       market: "KR"
       symbol: "005930"
   ```

5. IntelliJ에서 `com.quantlog.QuantlogApplicationKt`를 그냥 실행한다 (Run Configuration 기본값 그대로, 환경변수 설정 불필요). 실행 중엔 `http://localhost:8080`에서 매매 기록을 볼 수 있다.

### 1. 순서
1. **`mode: READ`** (시간 무관): 현재가 → 매수가능금액 → 잔고 조회. 토큰/계좌/응답 필드가 맞는지 확인. 오류가 나면 로그의 `KIS 오류 … [msg_cd] msg1` 를 본다.
2. **`mode: BUY`** (국내 정규장 중): 현재가 +0.5%를 호가 단위로 올림한 지정가로 1주 매수. 잔고에 보이면 성공. (파일 저장 후 다시 실행)
3. **`mode: SELL`**: 현재가 -0.5%를 호가 단위로 내림한 지정가로 1주 매도해 정리. 가격을 직접 정하려면 `quantlog.smoke.limit-price`(예: `273000`)를 지정한다.

값을 바꿀 때마다 `application-local.yml`을 저장하고 다시 실행하면 된다.

### 2. 시간 (한국시간, 서머타임 기준)
- 국내 장은 정규 개장일에만 열린다. 공휴일·연휴에는 휴장.
- 국내 주문 가능 시간: 평일 정규장 09:00~15:20. 이 시간 밖에서는 에러가 난다.
- 이 시간 밖에서는 `mode: READ`만 되고, **BUY/SELL은 위 시간대에만** 시도한다. 접수까지 확인되면 성공.
- 모의투자는 **일부 종목만 매매 가능**하다. `005930`이 거절되면 `application-local.yml`의 `quantlog.smoke.symbol`을 다른 대형주로 바꿔 본다.

### 3. 안전장치
- 주문은 항상 `RiskGuard` 를 거친다 (자본 배분 한도). 한도는 `application.yml` 의 `quantlog.risk`.
- 모의 도메인(`openapivts…`)만 사용한다. 실전 도메인은 코드에 없다.

## 개발

```bash
./gradlew ktlintFormat build   # 포맷 + 컴파일 + 테스트 (KIS 서버 없이 도는 단위 테스트, 통합 테스트는 localhost MySQL 필요)
```

## 모듈 구성 (2026-10-08)

Gradle 멀티모듈, **서버가 둘**이다(설계·계약은 [docs/서버-분리.md](docs/서버-분리.md), [docs/contracts/](docs/contracts/)).
- `gateway`(포트 8081): KIS 와 닿는 모든 것(키·토큰·REST·웹소켓·주문 실행·체결 원장 기록). KIS 키는 여기에만 둔다. 거의 재배포하지 않는다.
- `app`(포트 8080): 전략·화면·알림. 게이트웨이와 코드를 공유하지 않고(컴파일 의존 없음) HTTP·웹소켓·DB 테이블 계약으로만 만난다. 게이트웨이 주소·토큰은 `quantlog.gateway.base-url`(`QUANTLOG_GATEWAY_URL`)·`quantlog.gateway.token`(`QUANTLOG_GATEWAY_TOKEN`, 양쪽 같은 값).
- 빌드: `./gradlew bootJar` → `build/libs/gateway.jar`, `build/libs/app.jar`. 로컬 실행은 게이트웨이를 먼저 띄운다(`./gradlew :gateway:bootRun`, 그다음 `:app:bootRun`). 증권사 키 파일(`application-local.yml`)은 게이트웨이를 띄우는 디렉터리에 둔다.

## 해외 주식 제거에 따른 DB 정리 (2026-10-08)

`Market` enum 에서 NASDAQ/NYSE/AMEX 가 빠졌다. 기존 DB 에 해외 행이 남아 있으면 JPA 가 읽다가 실패하므로, **이 버전을 띄우기 전에** 한 번 지운다(되돌릴 수 없으니 필요하면 먼저 백업).

```sql
DELETE FROM trade WHERE market <> 'KR';
DELETE FROM account_holding WHERE market <> 'KR';
DELETE FROM symbol_strategy WHERE market <> 'KR';
DELETE FROM minute_candle WHERE market <> 'KR';
DELETE FROM paper_order WHERE market <> 'KR';
```

## 종목별 매매 설정 (DB)

오늘 살 종목, 익절·손절 %, 마틴게일 값은 코드가 아니라 DB의 `symbol_strategy` 테이블에 있다. 웹 화면 `/settings`(상단 메뉴 "설정")에서 바꾸고 종목도 추가하거나(감시 종목 목록의 정본이 이 테이블이다) SQL 로 UPDATE 하면 **재시작 없이** 다음 주기(1초 이내)부터 반영된다. 앱이 처음 뜰 때 없는 종목 행만 `application.yml` 기본값으로 채우고(KODEX 코스닥150레버리지만 `martingale`·`support_bounce_entry` 켬), 이미 있는 행은 덮어쓰지 않는다. 행이 없는 종목은 사지 않는다. 설정 화면의 "종목 추가"는 KIS 종목 마스터(`stock_master` 테이블, 코스피·코스닥 전체)를 이름·코드로 검색해 고르는 방식이다. 마스터는 테이블이 비어 있으면 앱 시작 때, 이후 매일 08:50 에 KIS 공개 파일에서 받아 갱신한다(`docs/kis-api/stock-master.md`).

```sql
-- 삼성전자 마틴게일 켜기 (매수 옵션이 하나도 안 켜진 종목은 사지 않는다)
UPDATE symbol_strategy SET martingale = 1 WHERE symbol = '005930';
-- 삼성전자 익절 +0.5% → +1%, 마틴게일 하락 트리거 0.5% → 1%
UPDATE symbol_strategy SET take_profit_percent = 1, martingale_drop_percent = 1 WHERE symbol = '005930';
```

진입 옵션은 종목별로 완전히 별개다: `martingale`(보유 중 추가매수만), `periodic_rebuy`(`periodic_rebuy_interval_minutes`분마다 보유 0주면 설정 수량), `support_bounce_entry`(저점 판단 진입, 보유 수량 무관). 이미 있는 행은 두 신규 컬럼이 0(꺼짐)으로 시작하므로 화면에서 켠다. 재진입 컬럼(`martingale_reentry_drop_percent`, `martingale_stop_reentry_drop_percent`)은 폐기됐으니 기존 DB에서는 `ALTER TABLE symbol_strategy DROP COLUMN martingale_reentry_drop_percent, DROP COLUMN martingale_stop_reentry_drop_percent;` 로 지운다(안 지우면 새 행 INSERT 가 실패한다). 자동매수(`auto_trade`) 스위치도 폐기됐다(2026-10-02) — 마찬가지로 `ALTER TABLE symbol_strategy DROP COLUMN auto_trade;` 로 지운다. 지우기 전엔 이 컬럼이 꺼져 있던 행도 매수 옵션만 켜져 있으면 바로 사기 시작한다.

컬럼: `periodic_rebuy_quantity`(재매수 1회 수량, 기본 1), `periodic_rebuy_interval_minutes`(재매수 간격 분, 기본 5), `support_bounce_quantity`(저점 판단 진입 1회 수량, 기본 1), `periodic_rebuy`, `support_bounce_entry`, `take_profit_percent`, `stop_loss_percent`(NULL=손절 보류, 마틴게일 켜면 `martingale_drop_percent` 이상이어야 함), `martingale`, `martingale_drop_percent`, `martingale_multiplier`, `martingale_max_stages`. 마틴게일 전용 `martingale_final_stage_stop_loss_percent` 컬럼은 폐기(2026-10-07, 손절 % 하나로 통합) — 기존 DB는 `UPDATE symbol_strategy SET stop_loss_percent = martingale_final_stage_stop_loss_percent WHERE martingale = 1 AND stop_loss_percent IS NULL;` 로 값을 옮긴 뒤 `ALTER TABLE symbol_strategy DROP COLUMN martingale_final_stage_stop_loss_percent;` 로 지운다(안 지우면 새 행 INSERT 가 실패한다). 비율은 % 단위(0.5 = 0.5%). 엔티티: `watchlist/SymbolStrategy.kt`. 기존 `buy_quantity` 컬럼은 폐기(법칙별 수량 2개로 분리, 2026-10-02) — 값을 옮기려면 `UPDATE symbol_strategy SET periodic_rebuy_quantity = buy_quantity, support_bounce_quantity = buy_quantity;` 후 `ALTER TABLE symbol_strategy DROP COLUMN buy_quantity;`.

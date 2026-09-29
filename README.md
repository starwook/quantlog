# quantlog

AI 자동매매 봇 + 판단 과정·수익률 공개 웹서비스. 기획은 [docs/기획서.md](docs/기획서.md), 웹 화면은 [docs/웹-화면-기획.md](docs/웹-화면-기획.md).

## 지금 되는 것

한투(KIS) **모의투자** 연동 + 매매 기록 DB/웹. 국내(KR) + 미국(NASDAQ/NYSE/AMEX) 지원. 실행하면 `http://localhost:8080`에서 계좌 요약·실현손익·매매 기록을 볼 수 있다 (Thymeleaf, 서버는 계속 떠 있음).

| 기능 | 위치 |
|---|---|
| 브로커 추상화 | `broker/BrokerClient.kt` |
| KIS 모의투자 구현 (시세·매수가능·잔고·지정가 주문) | `broker/kis/` |
| 청산 판정 (익절 +1% / 손절 -1% 시작, 목표가는 가장 가까운 호가로 맞춤, 설정으로 조정) | `strategy/ExitRule.kt` |
| 주문 전 리스크 가드 (주문금액·수량 상한) | `trading/RiskGuard.kt` |
| 연동 점검 실행기 (READ / BUY / SELL / CANDLES) | `trading/SmokeTestRunner.kt` |
| 청산 스케줄러 (정규장 동안 30초마다 잔고 확인 → 익절/손절 목표가에 닿으면 한 호가 낮게 전량 매도, `quantlog.exit.enabled`) | `trading/ExitScheduler.kt` |
| 국내 분봉 수집 (KIS 당일·최근 30건 → `minute_candle` 테이블에 누적) | `marketdata/` |
| 매매 기록 저장·조회·실현손익 계산(FIFO) | `position/` (MySQL, DB명 `quantlog`, 로컬 root/무비밀번호) |
| 웹 대시보드 (8080) | `position/TradeController.kt` + `resources/templates/trades.html` |

**아직 없는 것**: 자동 매수(진입 판단 규칙 미정), 타임스톱·체결 확인·휴장일 판단, 클라우드 배포(로컬 PC 꺼지면 같이 멈춤), 진입 판단(AI 필요 여부 재검토 중, `docs/기획서.md` 7장), 일일 손실 한도(킬스위치) 코드.

## 오늘 모의투자 테스트하기

### 0. 준비 (한 번만)
1. KIS Developers → 모의투자 신청 → **모의투자 앱키/시크릿**, **모의계좌번호** 확인 (해외주식 모의투자 신청 포함 `[확인 필요]`).
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
       log-raw: true
   quantlog:
     smoke:
       mode: "READ"       # READ → BUY → SELL 순서로 바꿔 가며 저장하고 재실행
       market: "NASDAQ"
       symbol: "AAPL"
   ```

5. IntelliJ에서 `com.quantlog.QuantlogApplicationKt`를 그냥 실행한다 (Run Configuration 기본값 그대로, 환경변수 설정 불필요). 실행 중엔 `http://localhost:8080`에서 매매 기록을 볼 수 있다. 국내 종목을 보려면 `market: "KR"`, `symbol: "005930"`처럼 바꾼다.

### 1. 순서
1. **`mode: READ`** (시간 무관): 현재가 → 매수가능금액 → 잔고 조회. 토큰/계좌/응답 필드가 맞는지 확인. 오류가 나면 로그의 `KIS 오류 … [msg_cd] msg1` 를 본다.
2. **`mode: BUY`** (미국 정규장 중): 현재가 +0.5%를 호가 단위로 올림한 지정가로 1주 매수. 잔고에 보이면 성공. (파일 저장 후 다시 실행)
3. **`mode: SELL`**: 현재가 -0.5%를 호가 단위로 내림한 지정가로 1주 매도해 정리. 가격을 직접 정하려면 `quantlog.smoke.limit-price`(예: `273000`)를 지정한다.

값을 바꿀 때마다 `application-local.yml`을 저장하고 다시 실행하면 된다.

### 2. 시간 (한국시간, 서머타임 기준)
- 국내 장은 정규 개장일에만 열린다. 공휴일·연휴에는 휴장.
- 미국 주문 가능 시간(포털 명시): **프리마켓 17:00~22:30, 정규장 22:30~05:00, 애프터마켓 05:00~07:00**. 같은 주문 API로 접수되며, 이 시간 밖에서는 에러가 난다.
- 이 시간 밖에서는 `mode: READ`만 되고, **BUY/SELL은 위 시간대에만** 시도한다. 프리마켓은 체결이 잘 안 될 수 있다(유동성). 접수까지 확인되면 성공.
- 미국 주간거래(10:00~18:00)는 모의투자 미지원이라 쓰지 않는다.
- 모의투자는 **일부 종목만 매매 가능**하다. `AAPL`이 거절되면 `application-local.yml`의 `quantlog.smoke.symbol`을 다른 대형주로 바꿔 본다.

### 3. 안전장치
- 주문은 항상 `RiskGuard` 를 거친다 (기본 1회 $1,000 / 10주 / ₩2,000,000). 한도는 `application.yml` 의 `quantlog.risk`.
- 모의 도메인(`openapivts…`)만 사용한다. 실전 도메인은 코드에 없다.

## 개발

```bash
./gradlew ktlintFormat build   # 포맷 + 컴파일 + 테스트 (KIS 서버 없이 도는 단위 테스트)
```

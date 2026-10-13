# quantlog

AI 자동매매 봇 + 판단 과정·수익률 공개 웹서비스. 기획은 [docs/기획서.md](docs/기획서.md), 웹 화면은 [docs/웹-화면-기획.md](docs/웹-화면-기획.md), 매매 원칙은 [playbook/principles.md](playbook/principles.md).

한투(KIS) **모의투자** 연동 + 매매 기록 DB/웹. 국내(KR) 주식 전용. 실행하면 `http://localhost:8080`에서 주문·실현손익·매매 기록을 볼 수 있다.

## 모듈 구성

Gradle 멀티모듈, **서버가 둘**이다(설계·계약은 [docs/서버-분리.md](docs/서버-분리.md), [docs/contracts/](docs/contracts/)).
- `gateway`(포트 8081): KIS 와 닿는 모든 것(키·토큰·REST·웹소켓·주문 실행·원문 기록)을 **해석 없이** 통과시키는 원문 통로. KIS 키는 여기에만 둔다. 거의 재배포하지 않는다.
- `app`(포트 8080): 전략·화면·알림. 게이트웨이와 코드를 공유하지 않고 HTTP·웹소켓·DB 테이블 계약으로만 만난다. 게이트웨이 주소·토큰은 `quantlog.gateway.base-url`(`QUANTLOG_GATEWAY_URL`)·`quantlog.gateway.token`(`QUANTLOG_GATEWAY_TOKEN`, 양쪽 같은 값).

## 로컬 실행

1. KIS Developers → 모의투자 신청 → **모의투자 앱키/시크릿**, **모의계좌번호** 확인.
2. 로컬 MySQL을 띄우고 DB를 만든다: `mysql -uroot -e "CREATE DATABASE IF NOT EXISTS quantlog;"` (접속 정보는 `application.yml`의 `spring.datasource`).
3. 템플릿을 복사해 앱키·시크릿·계좌번호를 채운다. **이 파일은 `.gitignore`에 있어 git에 올라가지 않는다.** 증권사 키 파일은 게이트웨이를 띄우는 디렉터리에 둔다.

   ```bash
   cp application-local.yml.example application-local.yml
   ```

4. 게이트웨이를 먼저, 그다음 앱을 띄운다 (IntelliJ 또는 `./gradlew :gateway:bootRun`, `./gradlew :app:bootRun`).

### 연동 점검 (`quantlog.smoke.mode`)
`application-local.yml`의 `quantlog.smoke.mode`를 `READ` → `BUY` → `SELL` 순서로 바꿔 가며 저장하고 재실행한다.
- `READ`(시간 무관): 현재가·매수가능금액·잔고 조회. 오류가 나면 로그의 `KIS 오류 … [msg_cd] msg1` 를 본다.
- `BUY`/`SELL`(국내 정규장 평일 09:00~15:20): 호가 단위로 맞춘 지정가로 1주 매매. 모의투자는 일부 종목만 가능하니 거절되면 `quantlog.smoke.symbol`을 바꿔 본다.

## 일일 리뷰 (학습 루프, 2026-10-12)
오늘 체결·보유 현황을 Claude 가 복기해 `playbook/journal/`(일지)과 `playbook/proposals/`(원칙 변경 제안)에 남긴다. 원칙 파일은 바꾸지 않는다.
- 키: `application-local.yml` 의 `quantlog.anthropic.api-key` (Anthropic 콘솔 API 키 — Claude 구독과 별개로 크레딧 결제).
- 수동 실행: 앱을 띄운 뒤 `/review` 화면(메뉴 "리뷰")의 "지금 리뷰 실행" 버튼 (API 비용 발생). 일지·제안도 이 화면에서 본다. 자동: `quantlog.review.enabled=true` → 평일 15:40.
- 선물옵션: `/futures` 화면(메뉴 "선물옵션")에서 코스피200 옵션 매물대(행사가별 미결제약정)와 선물 시세를 본다. 매물대는 평일 16:00 에 자동으로 모아 저장하고(`quantlog.futures.collect-enabled`, 한투 조회 100번 안팎·약 5분, 주문 없음) "지금 모으기" 버튼으로도 모은다. 사양·실측: `docs/kis-api/futures-options.md`.

## 개발

```bash
./gradlew ktlintFormat build   # 포맷 + 컴파일 + 테스트 (통합 테스트는 localhost MySQL 필요)
./gradlew bootJar              # build/libs/gateway.jar, app.jar
```

## 종목별 매매 설정 (DB)

살 종목, 익절·손절 %, 마틴게일 값은 DB의 `symbol_strategy` 테이블에 있다(엔티티 `watchlist/SymbolStrategy.kt`). 웹 화면 `/settings`에서 바꾸거나 SQL 로 UPDATE 하면 재시작 없이 다음 주기부터 반영된다. 행이 없는 종목은 사지 않는다. 종목 추가는 `stock_master`(코스피·코스닥 전체, 매일 08:50 갱신)를 이름·코드로 검색해 고른다. 진입 옵션(`martingale`, `periodic_rebuy`, `support_bounce_entry`)은 종목별로 별개이며 하나도 켜지 않으면 사지 않는다. 비율은 % 단위(0.5 = 0.5%).

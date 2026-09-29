# quantlog

AI 매매 판단 과정·수익률을 공개하는 데이트레이딩 봇 + 웹서비스. 롱 온리, 청산은 AI 없이 규칙(초기 익절 +1%/손절 -1%). **진입에 AI가 필요한지는 재검토 중** — 기획서 7장, playbook 참고. 1단계는 한투(KIS) 모의투자, 2단계는 토스증권 실계좌.

## 먼저 읽을 문서 (최신 상태가 어디 있는지)
- **`playbook/principles.md`** — 지금 매매 원칙(수치 포함)의 기준. 다른 문서와 어긋나면 여기를 따른다.
- `docs/기획서.md` — 전체 기획. 결정 사항은 맨 위 표, 열린 질문은 "미결 사항" 절
- `docs/웹-화면-기획.md` — 웹 화면 기획
- `docs/kis-api/README.md` — 한투 Open API 원문·확인된 TR ID·실측 제약(rate limit, OCO 미지원 등). 사양은 추측하지 말고 여기서 확인
- `docs/toss-api/README.md` — 토스 Open API 원문 (2단계용)
- `docs/워뇨띠-분석.md` — 참고 자료 (봇 파라미터 근거)
- `deploy/DEPLOY.md` — Oracle Cloud 배포 구조·절차 (서버 구성, 시크릿 위치, GitHub Secrets)
- `README.md` — 로컬 실행 방법

## 규칙
- 스택: Kotlin + Spring Boot + JPA + MySQL + Thymeleaf + ktlint + Gradle. 패키지는 도메인 기준.
- 증권사 API는 `BrokerClient` 인터페이스 뒤에 둔다 (한투 모의 → 토스 실계좌 전환이 구현체 교체로 끝나게).
- 모든 주문은 리스크 가드를 거친다. 우회 경로를 만들지 않는다.
- 시크릿(KIS 앱키/시크릿/계좌, 토스 client id/secret, Anthropic API 키 등)은 코드·로그·문서·커밋에 평문 금지 — `application-local.yml`(gitignore 대상)에만 적는다.
- **`application-local.yml`은 실제 시크릿이 든 파일이다. 테스트·스크립트가 이 정확한 경로를 쓰거나 지우게 만들지 않는다** (2026-09-29: 이 경로를 건드리는 테스트가 실제 키 파일을 삭제한 사고가 있었다). 파일 로딩 자체를 테스트하려면 임시 디렉터리를 쓰고, 그 외 테스트는 `spring.profiles.active`를 명시해 이 파일이 적용되지 않게 한다.
- 앱/봇은 사용자가 요청하지 않는 한 실행하지 않는다. 주문 API 호출은 반드시 사용자 확인 후.
- **배포**: `main`에 push/머지하면 GitHub Actions(`.github/workflows/deploy.yml`)가 빌드·테스트 후 Oracle Cloud 서버(Docker, 8080)에 자동 배포하고 **봇이 재시작된다** (모의계좌로 실제 주문이 나가는 봇). 머지는 사용자가 정한 뒤에만. 서버 접속(SSH 키)·서버 시크릿 동기화·서버 DB 조회는 사용자의 로컬 Mac(`~/.claude/agents/deploy-agent.md`)에서만 된다 — 클라우드 세션에서는 시도하지 말고 필요하면 사용자에게 알린다.
- 웹 화면의 보유 종목은 KIS 잔고를 직접 조회한다(`PortfolioService.accountSnapshot()`). 매매 판단 경로(스케줄러·리스크 가드)만 요청 한도 때문에 매매 기록 DB 기준(`snapshot()`)이다. 둘을 섞지 않는다.
- 테스트(`QuantlogApplicationTest`)는 MySQL(localhost:3306, root/무비밀번호, DB `quantlog`)이 떠 있어야 통과한다. CI는 서비스 컨테이너로 MySQL을 띄운다.
- 클래스·패키지 이름을 바꾸면 `docs/`, `playbook/`, `README.md`에서 옛 이름을 grep해 함께 고친다.
- push 전에 `docs/기획서.md`가 실제 코드/결정과 어긋나지 않는지 확인한다.
- 빌드/테스트: `./gradlew ktlintFormat build`. 실제 KIS/Anthropic 호출은 사용자 확인 후에만 실행한다.
- 화면 작업 시 `ux-design-review` 스킬 체크리스트를 적용한다.

## 원칙집(playbook) 관리 규칙
사용자가 매매 원칙·기준·자료를 말하면(예: "익절은 +2%쯤", "국내 500만원만 쓴다") 그 자리에서 `playbook/principles.md`에 반영하거나 확정 안 된 건 "아직 정하는 중"/토론 후보로 등록하고, `playbook/principles-log.md`에 날짜·이유·출처를 남긴다. 확정 안 된 걸 확정으로 쓰지 않는다. 자료를 받으면 `playbook/sources/`에 저장·요약한다. 원칙과 어긋나는 코드를 짜지 않는다.

# quantlog 배포

서버 공통 정보(접속, 방화벽, MySQL 구성)는 `~/.claude/agents/deploy-agent.md` 참고. 여기는 quantlog 전용 내용만 둔다.

## 구조
- 서버: `/opt/quantlog` — `app.jar`, `Dockerfile`, `docker-compose.yml`, `remote-deploy.sh` (배포 때마다 올라감) + `.env`, `application-local.yml`, `playbook/` (서버에만 있음)
- 컨테이너 `quantlog`, 포트 **8080** (운영/로컬 배포 모두 동일. 동시에 두 개 뜨지 않는다)
- DB: 서버에 설치된 호스트 MySQL의 `quantlog` DB, 유저 `admin`. 컨테이너에서 `host.docker.internal:3306`으로 접속
- 두 배포 방식은 같은 `deploy/deploy.sh`를 쓴다 (jar를 올리고 서버에서 `docker compose up -d --build`)
  1. GitHub push(main) → `.github/workflows/deploy.yml` (빌드·테스트 후 배포)
  2. 로컬 수동: `DEPLOY_KEY=<키 경로> DEPLOY_HOST=<IP> deploy/deploy.sh` (빌드·테스트 포함)
- 서버 쪽은 `flock`으로 동시 배포를 막고, 기동 로그(`Started QuantlogApplicationKt`)를 확인해 실패하면 이전 jar로 롤백한다.

## 서버에 한 번 만들어 두는 파일 (저장소에 없음)
- `/opt/quantlog/.env` (권한 600) — `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`
  - URL: `jdbc:mysql://host.docker.internal:3306/quantlog?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul&characterEncoding=UTF-8`
- `/opt/quantlog/application-local.yml` (권한 600) — 로컬 `application-local.yml`(KIS 키, 웹훅 등)을 그대로 복사. 값이 바뀌면 다시 복사한다.

## 시크릿 동기화 (deploy-agent가 담당)
로컬 `application-local.yml` 값을 바꿨으면 "서버 시크릿 동기화해줘"라고 한다 → `deploy/sync-secrets.sh` (복사 + 컨테이너 재시작 + 기동 확인). 복사 전에 서버에 가면 안 되는 로컬 전용 설정(`quantlog.smoke.mode` 등)이 채워져 있지 않은지 값 없이 키 이름만 확인한다. 자동 권한 검사에 막히면 사용자가 `! deploy/sync-secrets.sh`(환경변수 포함)를 실행한다.

## GitHub Secrets
`SSH_HOST`(서버 IP), `SSH_USER`(`ubuntu`), `SSH_KEY`(CI 전용 개인키 전체 내용)

## 주의
- 같은 KIS 계좌로 로컬 IDE 실행과 서버 봇이 동시에 돌면 주문이 중복될 수 있다. 서버 배포 중에는 로컬 봇을 끄거나 스케줄러 플래그(`QUANTLOG_ENTRY_ENABLED=false` 등)를 쓴다.
- 서버 `.env`에 `QUANTLOG_ENTRY_ENABLED` 등 플래그를 넣어 서버 봇을 끌 수 있다.

## TODO
- 서버 DB `symbol_strategy` 의 폐기된 재진입 컬럼 삭제 (2026-10-02 마틴게일 재진입 폐기, 아직 안 지움). 안 지우면 설정 화면에서 새 종목을 추가할 때 INSERT 가 실패한다(기존 행·매매에는 영향 없음). 배포 후 deploy-agent 가 실행:
  `ALTER TABLE symbol_strategy DROP COLUMN martingale_reentry_drop_percent, DROP COLUMN martingale_stop_reentry_drop_percent;`

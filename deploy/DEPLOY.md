# quantlog 배포

서버 공통 정보(접속, 방화벽, MySQL 구성)는 `~/.claude/agents/deploy-agent.md` 참고. 여기는 quantlog 전용 내용만 둔다.

## 구조
- 서버: `/opt/quantlog` — `gateway.jar`, `app.jar`, `Dockerfile`, `docker-compose.yml`, `remote-deploy.sh` (배포 때마다 올라감) + `.env`, `application-local.yml`, `playbook/` (서버에만 있음)
- 컨테이너 둘: **`quantlog-gateway`**(증권사 키·REST·실시간·주문·원장 기록, 포트 8081은 호스트에 열지 않고 앱이 컴포즈 네트워크로 `http://gateway:8081` 접속)와 **`quantlog`**(앱, 포트 **8080**). 설계는 `docs/서버-분리.md`.
- DB: 서버에 설치된 호스트 MySQL의 `quantlog` DB, 유저 `admin`. 컨테이너에서 `host.docker.internal:3306`으로 접속 (둘이 같은 DB를 쓴다)
- 두 배포 방식은 같은 `deploy/deploy.sh`를 쓴다 (jar 둘을 올리고 서버에서 `remote-deploy.sh` 가 서비스별로 `docker compose up -d --build <서비스>`)
  1. GitHub push(main) → `.github/workflows/deploy.yml` (빌드·테스트 후 배포)
  2. 로컬 수동: `DEPLOY_KEY=<키 경로> DEPLOY_HOST=<IP> deploy/deploy.sh` (빌드·테스트 포함)
- 순서는 **앱 → 게이트웨이**. **게이트웨이는 재배포가 드물어야 한다**: jar 내용(sha256)이 같으면 건드리지 않고, 바뀌었어도 장중(평일 08:30~15:31 KST)이면 미루고 앱만 배포한다. 장중에 꼭 올려야 하면 `FORCE_GATEWAY=1 deploy/deploy.sh` 또는 워크플로 수동 실행의 `force_gateway`. (게이트웨이를 다시 띄우면 증권사 웹소켓이 끊겨 체결통보를 놓칠 수 있다.)
- 서버 쪽은 `flock`으로 동시 배포를 막고, 서비스마다 기동 로그(`Started QuantlogApplicationKt` / `Started GatewayApplicationKt`)를 확인해 실패하면 그 서비스만 이전 jar로 롤백한다.
- **처음 두 서비스로 넘어오는 배포**: 앱 단계에서 옛 한 덩어리 `quantlog` 컨테이너가 새 앱으로 교체되고(KIS 웹소켓은 옛 서버가 내려가며 풀린다), 이어서 게이트웨이가 새로 뜬다(없으면 장중이어도 배포). 앱이 게이트웨이를 못 찾는 잠깐 동안은 주문·시세가 실패한다 — 장 마감 후에 하는 것을 권한다. 롤백으로 옛 한 덩어리 jar가 되살아나면 게이트웨이와 KIS 세션이 겹치니 그때는 게이트웨이를 먼저 내린다.

## 서버에 한 번 만들어 두는 파일 (저장소에 없음)
- `/opt/quantlog/.env` (권한 600) — `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`, **`QUANTLOG_GATEWAY_TOKEN`**(앱↔게이트웨이 공유 토큰, 둘이 같은 값. 임의의 긴 문자열)
  - URL: `jdbc:mysql://host.docker.internal:3306/quantlog?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul&characterEncoding=UTF-8`
- `/opt/quantlog/application-local.yml` (권한 600) — 로컬 `application-local.yml`(KIS 키, 웹훅 등)을 그대로 복사. 값이 바뀌면 다시 복사한다. 게이트웨이가 KIS 키를 읽고, 앱은 지금 웹훅 주소를 위해 같은 파일을 마운트한다(웹훅 주소를 `.env` 의 `QUANTLOG_NOTIFY_WEBHOOK_URL` 로 옮기면 앱 컨테이너에서 이 파일을 뗄 수 있다).

## 시크릿 동기화 (deploy-agent가 담당)
로컬 `application-local.yml` 값을 바꿨으면 "서버 시크릿 동기화해줘"라고 한다 → `deploy/sync-secrets.sh` (복사 + 게이트웨이·앱 재시작 + 기동 확인. **게이트웨이도 재시작되므로 장중에는 꼭 필요할 때만**). 복사 전에 서버에 가면 안 되는 로컬 전용 설정(`quantlog.smoke.mode` 등)이 채워져 있지 않은지 값 없이 키 이름만 확인한다. 자동 권한 검사에 막히면 사용자가 `! deploy/sync-secrets.sh`(환경변수 포함)를 실행한다.

## GitHub Secrets
`SSH_HOST`(서버 IP), `SSH_USER`(`ubuntu`), `SSH_KEY`(CI 전용 개인키 전체 내용)

## 주의
- 같은 KIS 계좌로 로컬 IDE 실행과 서버 봇이 동시에 돌면 주문이 중복될 수 있다. 서버 배포 중에는 로컬 봇을 끄거나 스케줄러 플래그(`QUANTLOG_ENTRY_ENABLED=false` 등)를 쓴다.
- 서버 `.env`에 `QUANTLOG_ENTRY_ENABLED` 등 플래그를 넣어 서버 봇을 끌 수 있다.

#!/usr/bin/env bash
# 로컬 application-local.yml 을 서버로 복사하고 컨테이너(게이트웨이, 앱)를 재시작한다 (앱은 시작할 때만 설정을 읽는다).
# 로컬 파일은 읽기만 한다. 사용: DEPLOY_KEY=<키 경로> DEPLOY_HOST=<IP> deploy/sync-secrets.sh
set -euo pipefail

: "${DEPLOY_HOST:?DEPLOY_HOST 필요}"
: "${DEPLOY_KEY:?DEPLOY_KEY 필요 (개인키 파일 경로)}"
DEPLOY_USER="${DEPLOY_USER:-ubuntu}"
REMOTE_DIR=/opt/quantlog
SSH_OPTS=(-i "$DEPLOY_KEY" -o BatchMode=yes -o StrictHostKeyChecking=accept-new)

cd "$(dirname "$0")/.."

scp "${SSH_OPTS[@]}" application-local.yml "$DEPLOY_USER@$DEPLOY_HOST:$REMOTE_DIR/application-local.yml"

# 배포와 같은 락을 쓴다 (배포 중에는 실패). 증권사 키는 게이트웨이가, 웹훅 URL 은 앱이 읽으므로 둘 다 다시 시작한다 — 게이트웨이를 먼저(앱이 붙을 곳).
# 게이트웨이 재시작은 실시간 연결·체결 기록이 잠깐 끊긴다 — 장중에는 꼭 필요할 때만 실행할 것.
ssh "${SSH_OPTS[@]}" "$DEPLOY_USER@$DEPLOY_HOST" "chmod 600 $REMOTE_DIR/application-local.yml"

restart() { # <서비스> <컨테이너> <기동 로그 클래스>
  ssh "${SSH_OPTS[@]}" "$DEPLOY_USER@$DEPLOY_HOST" \
    "flock -n $REMOTE_DIR/deploy.lock bash -c 'cd $REMOTE_DIR && docker compose restart $1 && \
    for _ in \$(seq 1 40); do docker logs --since 1m $2 2>&1 | grep -q \"Started $3\" && echo $1 재시작 성공 && exit 0; sleep 3; done; \
    echo $1 기동 확인 실패; docker logs --tail 30 $2 2>&1; exit 1'"
}

restart gateway quantlog-gateway GatewayApplicationKt
restart quantlog quantlog QuantlogApplicationKt

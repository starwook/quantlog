#!/usr/bin/env bash
# 로컬 application-local.yml 을 서버로 복사하고 컨테이너를 재시작한다 (앱은 시작할 때만 설정을 읽는다).
# 로컬 파일은 읽기만 한다. 사용: DEPLOY_KEY=<키 경로> DEPLOY_HOST=<IP> deploy/sync-secrets.sh
set -euo pipefail

: "${DEPLOY_HOST:?DEPLOY_HOST 필요}"
: "${DEPLOY_KEY:?DEPLOY_KEY 필요 (개인키 파일 경로)}"
DEPLOY_USER="${DEPLOY_USER:-ubuntu}"
REMOTE_DIR=/opt/quantlog
SSH_OPTS=(-i "$DEPLOY_KEY" -o BatchMode=yes -o StrictHostKeyChecking=accept-new)

cd "$(dirname "$0")/.."

scp "${SSH_OPTS[@]}" application-local.yml "$DEPLOY_USER@$DEPLOY_HOST:$REMOTE_DIR/application-local.yml"

# 배포와 같은 락을 쓴다 (배포 중에는 실패)
ssh "${SSH_OPTS[@]}" "$DEPLOY_USER@$DEPLOY_HOST" "chmod 600 $REMOTE_DIR/application-local.yml && \
  flock -n $REMOTE_DIR/deploy.lock bash -c 'cd $REMOTE_DIR && docker compose restart quantlog && \
  for _ in \$(seq 1 40); do docker logs --since 1m quantlog 2>&1 | grep -q \"Started QuantlogApplicationKt\" && echo 재시작 성공 && exit 0; sleep 3; done; \
  echo 기동 확인 실패; docker logs --tail 30 quantlog 2>&1; exit 1'"

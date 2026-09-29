#!/usr/bin/env bash
# 로컬 수동 배포와 GitHub Actions 가 같이 쓰는 스크립트. (서버 쪽 절차는 remote-deploy.sh)
#   로컬: DEPLOY_KEY=<키 경로> DEPLOY_HOST=<IP> deploy/deploy.sh
#   CI:   워크플로가 빌드를 먼저 하고 SKIP_BUILD=1 로 호출한다.
set -euo pipefail

: "${DEPLOY_HOST:?DEPLOY_HOST 필요}"
: "${DEPLOY_KEY:?DEPLOY_KEY 필요 (개인키 파일 경로)}"
DEPLOY_USER="${DEPLOY_USER:-ubuntu}"
REMOTE_DIR=/opt/quantlog
SSH_OPTS=(-i "$DEPLOY_KEY" -o BatchMode=yes -o StrictHostKeyChecking=accept-new)

cd "$(dirname "$0")/.."

if [ -z "${SKIP_BUILD:-}" ]; then
  ./gradlew ktlintCheck test bootJar
fi

JAR=$(ls build/libs/*.jar | grep -v -- '-plain' | head -1)

ssh "${SSH_OPTS[@]}" "$DEPLOY_USER@$DEPLOY_HOST" "mkdir -p $REMOTE_DIR/playbook"
scp "${SSH_OPTS[@]}" "$JAR" "$DEPLOY_USER@$DEPLOY_HOST:$REMOTE_DIR/app.jar.new"
scp "${SSH_OPTS[@]}" deploy/Dockerfile deploy/docker-compose.yml deploy/remote-deploy.sh \
  "$DEPLOY_USER@$DEPLOY_HOST:$REMOTE_DIR/"

# -n: 다른 배포가 진행 중이면 기다리지 않고 실패한다
ssh "${SSH_OPTS[@]}" "$DEPLOY_USER@$DEPLOY_HOST" \
  "flock -n $REMOTE_DIR/deploy.lock bash $REMOTE_DIR/remote-deploy.sh"

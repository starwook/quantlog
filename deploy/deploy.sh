#!/usr/bin/env bash
# 로컬 수동 배포와 GitHub Actions 가 같이 쓰는 스크립트. (서버 쪽 절차는 remote-deploy.sh)
#   로컬: DEPLOY_KEY=<키 경로> DEPLOY_HOST=<IP> deploy/deploy.sh
#   CI:   워크플로가 빌드를 먼저 하고 SKIP_BUILD=1 로 호출한다.
# 게이트웨이는 재배포가 드물어야 한다: 내용(jar 체크섬)이 안 바뀌었으면 건드리지 않고, 바뀌었어도 장중(평일 08:30~16:00 KST)에는 미루고
# 앱만 배포한다. 장중에 꼭 올려야 하면 FORCE_GATEWAY=1.
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

for jar in gateway.jar app.jar; do
  [ -f "build/libs/$jar" ] || { echo "build/libs/$jar 가 없다 — bootJar 를 먼저 돌릴 것"; exit 1; }
done

ssh "${SSH_OPTS[@]}" "$DEPLOY_USER@$DEPLOY_HOST" "mkdir -p $REMOTE_DIR/playbook"
scp "${SSH_OPTS[@]}" build/libs/gateway.jar "$DEPLOY_USER@$DEPLOY_HOST:$REMOTE_DIR/gateway.jar.new"
scp "${SSH_OPTS[@]}" build/libs/app.jar "$DEPLOY_USER@$DEPLOY_HOST:$REMOTE_DIR/app.jar.new"
scp "${SSH_OPTS[@]}" deploy/Dockerfile deploy/docker-compose.yml deploy/remote-deploy.sh \
  "$DEPLOY_USER@$DEPLOY_HOST:$REMOTE_DIR/"

# -n: 다른 배포가 진행 중이면 기다리지 않고 실패한다
ssh "${SSH_OPTS[@]}" "$DEPLOY_USER@$DEPLOY_HOST" \
  "FORCE_GATEWAY=${FORCE_GATEWAY:-} flock -n $REMOTE_DIR/deploy.lock bash $REMOTE_DIR/remote-deploy.sh"

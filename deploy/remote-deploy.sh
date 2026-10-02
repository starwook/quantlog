#!/usr/bin/env bash
# 서버 쪽 배포 스크립트. deploy.sh 가 올려서 flock 아래에서 실행한다 (CI/로컬 동시 배포 방지).
# 새 jar(app.jar.new)로 교체 → 컨테이너 재생성 → 기동 확인, 실패하면 이전 jar로 롤백.
set -euo pipefail
cd /opt/quantlog

[ -f app.jar ] && cp app.jar app.jar.prev
mv app.jar.new app.jar
docker compose up -d --build

wait_started() {
  for _ in $(seq 1 40); do
    # grep -q 는 첫 매치에서 바로 끝나 docker logs 가 SIGPIPE 로 죽고, pipefail 때문에 매치돼도 실패로 판정된다
    # (로그가 클 때). 끝까지 읽도록 출력만 버린다.
    docker logs quantlog 2>&1 | grep "Started QuantlogApplicationKt" >/dev/null && return 0
    sleep 3
  done
  return 1
}

if wait_started; then
  echo "배포 성공"
  docker image prune -f >/dev/null
  exit 0
fi

echo "기동 확인 실패 — 마지막 로그:"
docker logs --tail 50 quantlog 2>&1 || true
if [ -f app.jar.prev ]; then
  echo "이전 jar로 롤백"
  mv app.jar.prev app.jar
  docker compose up -d --build
fi
exit 1

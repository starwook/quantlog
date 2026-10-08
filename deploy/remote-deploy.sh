#!/usr/bin/env bash
# 서버 쪽 배포 스크립트. deploy.sh 가 올려서 flock 아래에서 실행한다 (CI/로컬 동시 배포 방지).
# 서비스(앱, 게이트웨이)마다 새 jar 로 교체 → 컨테이너 재생성 → 기동 확인, 실패하면 이전 jar 로 롤백.
#  - 앱을 먼저 배포한다. (기존 한 덩어리 서버에서 처음 넘어올 때 옛 서버가 내려가야 증권사 웹소켓 세션이 겹치지 않는다.)
#  - 게이트웨이는 jar 내용이 바뀌었을 때만, 그리고 장중이 아닐 때만(또는 FORCE_GATEWAY=1, 또는 게이트웨이가 아직 없을 때) 배포한다.
set -euo pipefail
cd /opt/quantlog

same_content() { [ -f "$1" ] && [ -f "$2" ] && [ "$(sha256sum < "$1")" = "$(sha256sum < "$2")" ]; }

wait_started() { # <container> <기동 로그>
  for _ in $(seq 1 40); do
    # grep -q 는 첫 매치에서 바로 끝나 docker logs 가 SIGPIPE 로 죽고, pipefail 때문에 매치돼도 실패로 판정된다
    # (로그가 클 때). 끝까지 읽도록 출력만 버린다.
    docker logs "$1" 2>&1 | grep "$2" >/dev/null && return 0
    sleep 3
  done
  return 1
}

market_hours() { # 평일 08:30~16:00 KST
  local dow hm
  dow=$(TZ=Asia/Seoul date +%u)
  hm=$(TZ=Asia/Seoul date +%H%M)
  [ "$dow" -le 5 ] && [ "$hm" -ge 0830 ] && [ "$hm" -lt 1600 ]
}

deploy_service() { # <서비스> <컨테이너> <jar 이름(확장자 제외)> <기동 로그>
  local service=$1 container=$2 jar=$3 marker=$4
  [ -f "$jar.jar" ] && cp "$jar.jar" "$jar.jar.prev"
  mv "$jar.jar.new" "$jar.jar"
  docker compose up -d --build "$service"
  if wait_started "$container" "$marker"; then
    echo "[$service] 배포 성공"
    return 0
  fi
  echo "[$service] 기동 확인 실패 — 마지막 로그:"
  docker logs --tail 50 "$container" 2>&1 || true
  if [ -f "$jar.jar.prev" ]; then
    echo "[$service] 이전 jar로 롤백"
    mv "$jar.jar.prev" "$jar.jar"
    docker compose up -d --build "$service"
  fi
  return 1
}

status=0

# 1) 앱
if same_content app.jar app.jar.new && docker ps --format '{{.Names}}' | grep -x quantlog >/dev/null; then
  echo "[app] jar 가 같고 실행 중이라 건너뜀"
  rm -f app.jar.new
else
  deploy_service quantlog quantlog app "Started QuantlogApplicationKt" || status=1
fi

# 2) 게이트웨이
gateway_running=$(docker ps --format '{{.Names}}' | grep -x quantlog-gateway || true)
if [ -n "$gateway_running" ] && same_content gateway.jar gateway.jar.new; then
  echo "[gateway] jar 가 같아 건너뜀"
  rm -f gateway.jar.new
elif [ -n "$gateway_running" ] && [ -z "${FORCE_GATEWAY:-}" ] && market_hours; then
  echo "[gateway] jar 가 바뀌었지만 장중이라 배포를 미룬다 (장 마감 후 배포하거나 FORCE_GATEWAY=1). 앱은 배포됨."
  rm -f gateway.jar.new
else
  deploy_service gateway quantlog-gateway gateway "Started GatewayApplicationKt" || status=1
fi

docker image prune -f >/dev/null
exit $status

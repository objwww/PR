#!/bin/sh
# E2E-M3-05 通知至少一次链 v2（§12）：测试机器人已收到、notify-app 未 ACK 时杀进程。
# v2 修正：v4/M3-04 教训——docker kill 不触发 unless-stopped 重启策略 →
#   KILL5 后显式 start（编排器语义）；加 control-app/notify-app/AM 预检。
# AM 侧前置（脚本外已做）：group_interval 临时 10s（batch-b 双轮配方，事后还原）、
#   nflog 已清、incident FIRING@12 无 run 无 pending（gen12 可被 r1 认领）。
# 用法: nohup sh cc-m330-e2e05b-drill.sh > /tmp/m330-e2e05b-drill.log 2>&1 &
set -u
TAG=am3-e2e05-0905
CURL="docker run --rm --network alert-ab curlimages/curl:8.8.0"
q() { docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tAc "$1" 2>/dev/null | tr -d ' \n'; }

echo "== 预检 =="
docker ps --filter name=deploy-notify-app-1 --format 'notify: {{.Status}}'
docker ps --filter name=deploy-control-app-1 --format 'control: {{.Status}}'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tAc "select count(*) from arena.oa_chaos_session where state='ACTIVE'"

echo "== 1. echo stub 加 20s 延迟（杀点窗口加宽；priority=1 胜 catch-all） =="
$CURL -s -X POST http://echo-receiver:8080/__admin/mappings -H 'Content-Type: application/json' -d '{
  "priority": 1,
  "request": {"urlPattern": "/am3/testbot.*", "method": "POST"},
  "response": {"status": 200, "jsonBody": {"echo": "am3-delayed"}, "fixedDelayMilliseconds": 20000}
}' | head -c 200
echo

echo "== 2. journal 基线 =="
B=$($CURL -s "http://echo-receiver:8080/__admin/requests" | grep -c "am3/testbot" || echo 0)
echo "baseline=$B"

echo "== 3. 启动 S3 批 =="
cd /opt/build/pr
docker rm -f eval-runner-$TAG 2>/dev/null || true
nohup sh /tmp/cc-m330-evalrun.sh deploy/alert/eval/eval-scenarios-s3.yml "$TAG" \
  > /tmp/m330-$TAG-run.log 2>&1 &
sleep 5
docker ps --filter name=eval-runner-$TAG --format '{{.Names}} {{.Status}}'

echo "== 4. 看武犬：journal 增长即 kill notify-app + 显式拉起（最多 25min） =="
KILL5=MISSED
i=0
until [ "$i" -gt 1500 ]; do
  N=$($CURL -s "http://echo-receiver:8080/__admin/requests" | grep -c "am3/testbot" || echo 0)
  if [ "${N:-0}" -gt "${B:-0}" ] 2>/dev/null; then
    docker kill deploy-notify-app-1 >/dev/null 2>&1
    KILL5="FIRED at $(date +%T) journal=$N baseline=$B"
    break
  fi
  docker ps --filter name=eval-runner-$TAG --format '{{.Status}}' | grep -q Up || { echo "batch exited before delivery"; break; }
  sleep 1; i=$((i+1))
done
echo "KILL5=$KILL5"
sleep 5
docker start deploy-notify-app-1 >/dev/null 2>&1
sleep 20
docker ps --filter name=deploy-notify-app-1 --format '{{.Names}} {{.Status}}'

echo "== 5. 等批退出（最多 30min） =="
i=0
while docker ps --filter name=eval-runner-$TAG --format '{{.Status}}' | grep -q Up; do
  [ "$i" -gt 360 ] && { echo "TIMEOUT waiting batch"; break; }
  sleep 5; i=$((i+1))
done

echo "== 6. 恢复：删延迟 stub，重录原 catch-all（am0-catchall 同形） =="
$CURL -s -X POST http://echo-receiver:8080/__admin/mappings/reset | head -c 100
echo
$CURL -s -X POST http://echo-receiver:8080/__admin/mappings -H 'Content-Type: application/json' -d '{
  "name": "am0-catchall",
  "request": {"urlPattern": "/.*", "method": "ANY"},
  "response": {"status": 200, "jsonBody": {"echo": "am0-ok"}}
}' | head -c 200
echo
echo "drill done at $(date +%T)"
exit 0

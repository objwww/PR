#!/bin/sh
set -e
cd /opt/build/pr/deploy
TOK=$(grep '^CHAOS_ADMIN_TOKEN=' .env | cut -d= -f2- | tr -d '\r"')
JUDGE_BASE=$(grep '^OPENAI_COMPAT_BASE_URL=' .env | cut -d= -f2- | tr -d '\r"')
JUDGE_KEY=$(grep '^AGENT_MODEL_API_KEY=' .env | cut -d= -f2- | tr -d '\r"')
echo '---关闭探针会话---'
docker run --rm --network eval-mgmt curlimages/curl:latest -s -X POST "http://arena-chaos-admin:8080/chaos/F1/off" \
  -H "X-Admin-Token: $TOK" -H 'Content-Type: application/json' \
  -d '{"scenarioId":"chaos-eval-dbgprobe-s3-r1","expectedGeneration":0}' --max-time 10 2>/dev/null | head -c 120
echo ''
echo '---std worker 换新 run-tag 重建---'
docker inspect eval-worker-std --format '{{range .Config.Env}}{{println .}}{{end}}' > /tmp/std2.env
NEWTAG="p6$(date +%H%M%S)"
if grep -q '"run-tag":"[^"]*"' /tmp/std2.env; then
  sed -i "s|\"run-tag\":\"[^\"]*\"|\"run-tag\":\"$NEWTAG\"|" /tmp/std2.env
  echo "run-tag replaced -> $NEWTAG"
else
  sed -i "s|\"app\":{\"alert\":{\"eval\":{|&\"run-tag\":\"$NEWTAG\",|" /tmp/std2.env
  echo "run-tag added -> $NEWTAG"
fi
grep -o '"run-tag":"[^"]*"' /tmp/std2.env
grep -o '"judge":{[^}]*}' /tmp/std2.env | sed -E 's/(api-key..)...*/\1**/'
docker rm -f eval-worker-std >/dev/null 2>&1 || true
docker run -d --name eval-worker-std \
  --entrypoint java \
  --network eval-mgmt --network alert-net \
  -v /opt/build/pr/deploy/alert/eval:/eval:ro \
  --env-file /tmp/std2.env \
  pr-agent/control-app:0.0.1-SNAPSHOT \
  -jar /app/app.jar --spring.profiles.active=eval \
  > /dev/null
sleep 28
docker logs eval-worker-std 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | grep -E '评测执行注册表装载|Started ControlApplication|Application run failed' | tail -2

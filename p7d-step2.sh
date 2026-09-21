#!/bin/sh
set -e
cd /opt/build/pr/deploy
curl -s -o /dev/null -w 'health=%{http_code}\n' http://127.0.0.1:8080/actuator/health
JUDGE_BASE=$(grep '^OPENAI_COMPAT_BASE_URL=' .env | cut -d= -f2- | tr -d '\r"')
JUDGE_KEY=$(grep '^AGENT_MODEL_API_KEY=' .env | cut -d= -f2- | tr -d '\r"')
docker inspect eval-worker-std --format '{{range .Config.Env}}{{println .}}{{end}}' > /tmp/std.env
if grep -q '"judge":' /tmp/std.env; then
  echo 'judge already configured'
else
  sed -i 's|{"app":{"alert":{"eval":{|&"judge":{"base-url":"'"$JUDGE_BASE"'","api-key":"'"$JUDGE_KEY"'","model":"deepseek-v3"},|' /tmp/std.env
  echo 'judge keys injected'
fi
grep -o '"judge":{[^}]*}' /tmp/std.env | sed -E 's/(api-key..)...*/\1**/'
docker rm -f eval-worker-std >/dev/null 2>&1 || true
docker run -d --name eval-worker-std \
  --entrypoint java \
  --network eval-mgmt --network alert-net \
  -v /opt/build/pr/deploy/alert/eval:/eval:ro \
  --env-file /tmp/std.env \
  pr-agent/control-app:0.0.1-SNAPSHOT \
  -jar /app/app.jar --spring.profiles.active=eval \
  > /dev/null
sleep 28
docker logs eval-worker-std 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | grep -E '评测执行注册表装载|Started ControlApplication|Application run failed' | tail -3
echo '---预清场检查---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c "select incident_key||' '||status from incident where incident_key in ('alertname=ArenaDuplicateOrders|service=order-arena|job=order-arena','alertname=ArenaIllegalTransitions|service=order-arena|job=order-arena','alertname=ArenaOrderStuck|service=order-arena|job=order-arena');"

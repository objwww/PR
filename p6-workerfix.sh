#!/bin/sh
set -e
cd /opt/build/pr/deploy
JUDGE_BASE=$(grep '^OPENAI_COMPAT_BASE_URL=' .env | cut -d= -f2- | tr -d '\r"')
JUDGE_KEY=$(grep '^AGENT_MODEL_API_KEY=' .env | cut -d= -f2- | tr -d '\r"')
docker inspect eval-worker-std --format '{{range .Config.Env}}{{println .}}{{end}}' > /tmp/std8.env
NEWTAG="p6$(date +%H%M%S)"
sed -i "s|\"run-tag\":\"[^\"]*\"|\"run-tag\":\"$NEWTAG\"|" /tmp/std8.env
echo "run-tag -> $(grep -o '\"run-tag\":\"[^\"]*\"' /tmp/std8.env)"
docker rm -f eval-worker-std >/dev/null 2>&1 || true
docker run -d --name eval-worker-std \
  --entrypoint java \
  --network eval-mgmt --network alert-net \
  -v /opt/build/pr/deploy/alert/eval:/eval:ro \
  --env-file /tmp/std8.env \
  -e JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75" \
  --memory 1g \
  pr-agent/control-app:0.0.1-SNAPSHOT \
  -jar /app/app.jar --spring.profiles.active=eval \
  > /dev/null
sleep 30
docker logs eval-worker-std 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | grep -E '评测执行注册表装载|Started ControlApplication|孤儿|Application run failed' | tail -3
echo '---run A 终态核对---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c "select state||' '||coalesce(terminal_reason,'-') from eval_run where id::text like '7cd1356a%';"

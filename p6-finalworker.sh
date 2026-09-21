#!/bin/sh
docker rm -f eval-worker-std >/dev/null 2>&1 || true
sleep 3
cd /opt/build/pr/deploy
NEWTAG="p6$(date +%H%M%S)"
docker inspect eval-worker-std --format '{{range .Config.Env}}{{println .}}{{end}}' > /tmp/stdX.env 2>/dev/null || true
if [ ! -s /tmp/stdX.env ]; then
  JUDGE_BASE=$(grep '^OPENAI_COMPAT_BASE_URL=' .env | cut -d= -f2- | tr -d '\r"')
  JUDGE_KEY=$(grep '^AGENT_MODEL_API_KEY=' .env | cut -d= -f2- | tr -d '\r"')
  cat > /tmp/stdX.env <<EOF
SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/pr_agent
SPRING_DATASOURCE_USERNAME=eval_app
SPRING_DATASOURCE_PASSWORD=$(grep '^EVAL_DB_PASSWORD=' .env | cut -d= -f2- | tr -d '\r"')
SPRING_FLYWAY_ENABLED=false
SPRING_APPLICATION_JSON={"app":{"alert":{"eval":{"registry-path":"file:/eval/eval-scenarios.yml","lexicon-path":"file:/eval/synonym-lexicon-v1.yml","dataset-version":"eval-ds-1","model":"deepseek-v3","prompt-version":"am4-native-v5","tool-registry-digest":"9cc4677b27183a84107617080148b3c46ca44ea63b9105500939f99d6dfe257d","provider-fingerprint":"litellm:deepseek-v3@dashscope","alert-rule-digest":"3bbc81f38f2f14103219a682bf352a36f9741aaf407e7c32a0f31d9caa2859a5","grader-version":"grader-am3-v1","webhook-url":"http://control-app:8080/webhooks/alertmanager","webhook-bearer":"$(grep '^ALERTMANAGER_WEBHOOK_BEARER_TOKEN=' .env | cut -d= -f2- | tr -d '\r"')","judge":{"base-url":"$JUDGE_BASE","api-key":"$JUDGE_KEY","model":"deepseek-v3"},"litellm":{"base-url":"http://litellm-am3:4000","master-key":"$(docker inspect litellm-am3 --format '{{range .Config.Env}}{{println .}}{{end}}' | grep -E '^LITELLM_MASTER_KEY=' | cut -d= -f2- | tr -d '\r"')"}},"eval":{"launch":{"enabled":true,"modes":"L","max-rounds":30},"rounds":2}}}
EOF
fi
sed -i 's|"SPRING_APPLICATION_JSON={|&|' /tmp/stdX.env
NEWTAG2="p6$(date +%H%M%S)b"
sed -i "s|\"run-tag\":\"[^\"]*\"|\"run-tag\":\"$NEWTAG2\"|" /tmp/stdX.env
echo "run-tag -> $(grep -o '\"run-tag\":\"[^\"]*\"' /tmp/stdX.env)"
docker run -d --name eval-worker-std \
  --entrypoint java \
  --network eval-mgmt --network alert-net \
  -v /opt/build/pr/deploy/alert/eval:/eval:ro \
  --env-file /tmp/stdX.env \
  -e JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75" \
  --memory 1g \
  pr-agent/control-app:0.0.1-SNAPSHOT \
  -jar /app/app.jar --spring.profiles.active=eval \
  > /dev/null
sleep 28
docker logs eval-worker-std 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | grep -E '评测执行注册表装载|Started ControlApplication|孤儿|Application run failed' | tail -4
echo '---eccb56f2 终态---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c "select state||' '||coalesce(terminal_reason,'-') from eval_run where id::text like 'eccb56f2%';"

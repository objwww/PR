#!/bin/sh
# p12a Test A 准备：旗标现状 → paymentFailure=50% → worker 重建（自动 run-tag）
set -e
cd /opt/build/pr/deploy

echo '== [0] 清窗检查 =='
A=$(docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
  "select count(*) from arena.oa_chaos_session where state in ('ACTIVE','RECOVERING')")
E=$(docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
  "select count(*) from eval_run where state in ('RUNNING','SCORING','PENDING')")
D=$(docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
  "select count(*) from drill_job where state not in ('CLOSED','FAILED','DONE','RECOVERY_FAILED','CANCELLED')" 2>/dev/null || echo 0)
echo "chaos=$A eval=$E drill=$D"
[ "$A" = "0" ] && [ "$E" = "0" ] && [ "$D" = "0" ] || { echo WINDOW_BUSY; exit 8; }

echo '== [1] 旗标当前态（GET 尝试）=='
docker exec flagd-admin-am3 python3 -c "import urllib.request;print(urllib.request.urlopen('http://127.0.0.1:8081/flags',timeout=5).read().decode()[:400])" 2>&1 || echo '(GET 不支持，直接设)'

echo '== [2] paymentFailure -> 50% =='
docker exec flagd-admin-am3 python3 -c "import urllib.request,json;req=urllib.request.Request('http://127.0.0.1:8081/flags',data=json.dumps({'flag':'paymentFailure','variant':'50%'}).encode(),headers={'Content-Type':'application/json'});print(urllib.request.urlopen(req).read().decode())"
sleep 15

echo '== [3] worker 重建（无 run-tag = 按批自动派生，BA-190 W1）=='
JUDGE_BASE=$(grep '^OPENAI_COMPAT_BASE_URL=' .env | cut -d= -f2- | tr -d '\r"')
JUDGE_KEY=$(grep '^AGENT_MODEL_API_KEY=' .env | cut -d= -f2- | tr -d '\r"')
LITELLM_MASTER=$(docker inspect litellm-am3 --format '{{range .Config.Env}}{{println .}}{{end}}' | grep -E '^LITELLM_MASTER_KEY=' | cut -d= -f2- | tr -d '\r')
EVAL_PASS=$(grep '^EVAL_DB_PASSWORD=' .env | cut -d= -f2- | tr -d '\r')
BEARER=$(grep '^ALERTMANAGER_WEBHOOK_BEARER_TOKEN=' .env | cut -d= -f2- | tr -d '\r')
CHAOS_TOKEN=$(grep '^CHAOS_ADMIN_TOKEN=' .env | cut -d= -f2- | tr -d '\r')
PROMPT_DIGEST=$(sha256sum /opt/build/pr/control-app/src/main/java/com/objwww/pr/control/infrastructure/nativeexec/NativeInvestigationExecutor.java | cut -d' ' -f1)
TOOL_DIGEST=$(cat /opt/build/pr/control-app/src/main/java/com/objwww/pr/control/infrastructure/tool/*.java | sha256sum | cut -d' ' -f1)
RULE_DIGEST=$(cat /opt/build/pr/deploy/alert/prometheus/rules/*.yml | sha256sum | cut -d' ' -f1)
docker rm -f eval-worker-std >/dev/null 2>&1 || true
docker run -d --name eval-worker-std \
  --entrypoint java \
  --network eval-mgmt --network alert-net \
  -v /opt/build/pr/deploy/alert/eval:/eval:ro \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/pr_agent \
  -e SPRING_DATASOURCE_USERNAME=eval_app \
  -e SPRING_DATASOURCE_PASSWORD="$EVAL_PASS" \
  -e SPRING_FLYWAY_ENABLED=false \
  -e CHAOS_ADMIN_TOKEN="$CHAOS_TOKEN" \
  -e JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75" \
  --memory 1g \
  pr-agent/control-app:0.0.1-SNAPSHOT \
  -jar /app/app.jar --spring.profiles.active=eval \
  --app.alert.eval.registry-path=file:/eval/eval-scenarios.yml \
  --app.alert.eval.lexicon-path=file:/eval/synonym-lexicon-v1.yml \
  --app.alert.eval.dataset-version=eval-ds-1 \
  --app.alert.eval.model=deepseek-v3 \
  --app.alert.eval.prompt-version=am4-native-v5 \
  --app.alert.eval.prompt-digest="$PROMPT_DIGEST" \
  --app.alert.eval.tool-registry-digest="$TOOL_DIGEST" \
  --app.alert.eval.provider-fingerprint=litellm:deepseek-v3@dashscope \
  --app.alert.eval.alert-rule-digest="$RULE_DIGEST" \
  --app.alert.eval.grader-version=grader-am3-v1 \
  --app.alert.eval.webhook-url=http://control-app:8080/webhooks/alertmanager \
  --app.alert.eval.webhook-bearer="$BEARER" \
  --app.alert.eval.judge.base-url="$JUDGE_BASE" \
  --app.alert.eval.judge.api-key="$JUDGE_KEY" \
  --app.alert.eval.judge.model=deepseek-v3 \
  --app.alert.eval.litellm.base-url=http://litellm-am3:4000 \
  --app.alert.eval.litellm.master-key="$LITELLM_MASTER" \
  --app.alert.eval.resolver-skew-seconds=60 \
  --app.eval.launch.enabled=true \
  --app.eval.rounds=1 \
  > /var/log/eval-worker-std.log 2>&1
sleep 25
docker logs eval-worker-std 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | grep -E 'Started ControlApplication|Application run failed' | tail -1
echo P12A_PREP_OK

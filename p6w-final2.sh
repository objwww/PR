#!/bin/sh
# p6w-final v2：补齐 evalRunMetadata 六元数据键（digests 在 worker 启动时现算）
set -e
cd /opt/build/pr/deploy
CHAOS_TOKEN=$(grep '^CHAOS_ADMIN_TOKEN=' .env | cut -d= -f2- | tr -d '\r"')
JUDGE_BASE=$(grep '^OPENAI_COMPAT_BASE_URL=' .env | cut -d= -f2- | tr -d '\r"')
JUDGE_KEY=$(grep '^AGENT_MODEL_API_KEY=' .env | cut -d= -f2- | tr -d '\r"')
LITELLM_MASTER=$(docker inspect litellm-am3 --format '{{range .Config.Env}}{{println .}}{{end}}' | grep -E '^LITELLM_MASTER_KEY=' | cut -d= -f2- | tr -d '\r')
EVAL_PASS=$(grep '^EVAL_DB_PASSWORD=' .env | cut -d= -f2- | tr -d '\r')
BEARER=$(grep '^ALERTMANAGER_WEBHOOK_BEARER_TOKEN=' .env | cut -d= -f2- | tr -d '\r')
cd /opt/build/pr
PROMPT_DIGEST=$(sha256sum control-app/src/main/java/com/objwww/pr/control/infrastructure/nativeexec/NativeInvestigationExecutor.java | cut -d' ' -f1)
TOOL_DIGEST=$(cat control-app/src/main/java/com/objwww/pr/control/infrastructure/tool/*.java | sha256sum | cut -d' ' -f1)
RULE_DIGEST=$(cat deploy/alert/prometheus/rules/*.yml | sha256sum | cut -d' ' -f1)
cd deploy

nohup docker run --rm --name eval-worker-p6real \
  --entrypoint java \
  --network eval-mgmt --network alert-net \
  -v /opt/build/pr/deploy/alert/eval:/eval:ro \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/pr_agent \
  -e SPRING_DATASOURCE_USERNAME=eval_app \
  -e SPRING_DATASOURCE_PASSWORD="$EVAL_PASS" \
  -e SPRING_FLYWAY_ENABLED=false \
  -e CHAOS_ADMIN_TOKEN="$CHAOS_TOKEN" \
  -e JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=50" \
  pr-agent/control-app:0.0.1-SNAPSHOT \
  -jar /app/app.jar --spring.profiles.active=eval \
  --app.alert.eval.registry-path=file:/eval/eval-scenarios.yml \
  --app.alert.eval.lexicon-path=file:/eval/synonym-lexicon-v1.yml \
  --app.alert.eval.dataset-version=eval-ds-1 \
  --app.alert.eval.model=glm-5 \
  --app.alert.eval.prompt-version=am3-rca-v2 \
  --app.alert.eval.prompt-digest="$PROMPT_DIGEST" \
  --app.alert.eval.tool-registry-digest="$TOOL_DIGEST" \
  --app.alert.eval.provider-fingerprint=litellm:glm-5@dashscope \
  --app.alert.eval.alert-rule-digest="$RULE_DIGEST" \
  --app.alert.eval.grader-version=grader-p6-real-e2e-v1 \
  --app.alert.eval.webhook-url=http://control-app:8080/webhooks/alertmanager \
  --app.alert.eval.webhook-bearer="$BEARER" \
  --app.alert.eval.judge.base-url="$JUDGE_BASE" \
  --app.alert.eval.judge.api-key="$JUDGE_KEY" \
  --app.alert.eval.judge.model=qwen3-max \
  --app.alert.eval.litellm.base-url=http://litellm-am3:4000 \
  --app.alert.eval.litellm.master-key="$LITELLM_MASTER" \
  --app.eval.rounds=2 \
  > /tmp/p6-worker.log 2>&1 &
echo "worker pid=$!"
sleep 28
grep -E 'Started ControlApplication|评测执行注册表装载|ERROR|REJECTED|Application run failed' /tmp/p6-worker.log | tail -6

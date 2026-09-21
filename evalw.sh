#!/bin/sh
# 标准评测 worker（eval-ds-1 / 15 场景注册表）——2026-09-17 根因评分贯通批重建
# 镜像必须与控制面同源（WorkerSchemaFreshnessGuard 对照 flyway 最大版本自拒陈旧镜像）
set -eu
cd /opt/build/pr

sha() { sha256sum "$1" | cut -d" " -f1; }
PROMPT_DIGEST=$(sha control-app/src/main/java/com/objwww/pr/control/infrastructure/config/AlertAm4Config.java)
TOOL_DIGEST=$(cat control-app/src/main/java/com/objwww/pr/control/infrastructure/tool/*.java | sha256sum | cut -d" " -f1)
RULE_DIGEST=$(cat deploy/alert/prometheus/rules/*.yml | sha256sum | cut -d" " -f1)
EVAL_PASS=$(grep "^EVAL_DB_PASSWORD=" deploy/.env | cut -d= -f2)
BEARER=$(grep "^ALERTMANAGER_WEBHOOK_BEARER_TOKEN=" deploy/.env | cut -d= -f2)
CHAOS_TOKEN=$(grep "^CHAOS_ADMIN_TOKEN=" deploy/.env | cut -d= -f2)
LITELLM_MK=$(grep "^LITELLM_MASTER_KEY=" deploy/alert/.env | cut -d= -f2)
# Jev 增强直通（deploy/.env 单源；旗标 alert_runtime_flag.jev_enhanced 按 run 冻结——A/B 双臂只切旗标）
JEV_MODE=$(grep "^AGENT_MODEL_JEV_MODE=" deploy/.env | cut -d= -f2)
JEV_KEY=$(grep "^AGENT_MODEL_API_KEY_JEV=" deploy/.env | cut -d= -f2)
JEV_URL=$(grep "^AGENT_MODEL_BASE_URL_JEV=" deploy/.env | cut -d= -f2)
JEV_MIN=$(grep "^AGENT_MODEL_JEV_MIN_POOL=" deploy/.env | cut -d= -f2)
JEV_MAX=$(grep "^AGENT_MODEL_JEV_MAX_SELECTED=" deploy/.env | cut -d= -f2)
# 工具 R10 服务范围面直通（与控制面同源，2026-09-21 S26 实证：worker 缺省 change/rag/prom
# 仅 control-app 单服务，order-arena 场景全 INVALID_ARGS 烧步数致 STEPS_EXHAUSTED 未决）
ALW_PROM=$(grep "^APP_ALERT_AM4_PROMETHEUS_SERVICE_ALLOWLIST=" deploy/.env | cut -d= -f2)
ALW_LOGS=$(grep "^APP_ALERT_AM4_LOGS_SERVICE_ALLOWLIST=" deploy/.env | cut -d= -f2)
ALW_CHANGE=$(grep "^APP_ALERT_AM4_CHANGE_SERVICE_ALLOWLIST=" deploy/.env | cut -d= -f2)
ALW_RAG=$(grep "^APP_ALERT_RAG_SERVICE_ALLOWLIST=" deploy/.env | cut -d= -f2)
# RUN_TAG 不注入（2026-09-21 A/B 窗口实证：静态 tag=worker 生命周期级，同 worker 第二批必撞
# uq_chaos_scenario 永存行 409 全灭；缺省走 BA-190 W1 按批派生 r<evalRunId> 天然唯一）

SPRING_JSON=$(printf "{\"app\":{\"alert\":{\"eval\":{\"registry-path\":\"file:/eval/eval-scenarios.yml\",\"lexicon-path\":\"file:/eval/synonym-lexicon-v1.yml\",\"dataset-version\":\"eval-ds-1\",\"model\":\"deepseek-v3\",\"prompt-version\":\"am4-native-v5\",\"prompt-digest\":\"%s\",\"tool-registry-digest\":\"%s\",\"provider-fingerprint\":\"litellm:deepseek-v3@dashscope\",\"alert-rule-digest\":\"%s\",\"grader-version\":\"grader-am3-v1\",\"webhook-url\":\"http://control-app:8080/webhooks/alertmanager\",\"webhook-bearer\":\"%s\"}},\"eval\":{\"launch\":{\"enabled\":true,\"modes\":\"L\",\"max-rounds\":30},\"rounds\":2}}}" "$PROMPT_DIGEST" "$TOOL_DIGEST" "$RULE_DIGEST" "$BEARER")

docker rm -f eval-worker-std 2>/dev/null || true
nohup docker run --rm --name eval-worker-std \
  --entrypoint java \
  --network eval-mgmt --network alert-net \
  -v /opt/build/pr/deploy/alert/eval:/eval:ro \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/pr_agent \
  -e SPRING_DATASOURCE_USERNAME=eval_app \
  -e SPRING_DATASOURCE_PASSWORD="$EVAL_PASS" \
  -e SPRING_FLYWAY_ENABLED=false \
  -e SPRING_APPLICATION_JSON="$SPRING_JSON" \
  -e APP_ALERT_R7_PRIMARY_BUDGET_TOKENS=200000 \
  -e CHAOS_ADMIN_TOKEN="$CHAOS_TOKEN" \
  -e APP_ALERT_EVAL_LITELLM_BASE_URL=http://litellm:4000 \
  -e APP_ALERT_EVAL_LITELLM_MASTER_KEY="$LITELLM_MK" \
  -e APP_ALERT_EVAL_JUDGE_BASE_URL=http://litellm:4000 \
  -e APP_ALERT_EVAL_JUDGE_API_KEY="$LITELLM_MK" \
  -e APP_ALERT_EVAL_JUDGE_MODEL=deepseek-v3 \
  -e AGENT_MODEL_JEV_MODE="$JEV_MODE" \
  -e AGENT_MODEL_API_KEY_JEV="$JEV_KEY" \
  -e AGENT_MODEL_BASE_URL_JEV="$JEV_URL" \
  -e AGENT_MODEL_JEV_MIN_POOL="$JEV_MIN" \
  -e AGENT_MODEL_JEV_MAX_SELECTED="$JEV_MAX" \
  -e APP_ALERT_R7_INPUT_CAPTURE=redacted \
  -e APP_ALERT_R7_OUTPUT_CAPTURE=redacted \
  -e APP_DRILL_LAUNCH_ENABLED=true \
  -e APP_DRILL_TARGET_ENVS=arena-195 \
  -e APP_ALERT_AM4_PROMETHEUS_SERVICE_ALLOWLIST="$ALW_PROM" \
  -e APP_ALERT_AM4_LOGS_SERVICE_ALLOWLIST="$ALW_LOGS" \
  -e APP_ALERT_AM4_CHANGE_SERVICE_ALLOWLIST="$ALW_CHANGE" \
  -e APP_ALERT_RAG_SERVICE_ALLOWLIST="$ALW_RAG" \
  -e JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=50" \
  pr-agent/control-app:0.0.1-SNAPSHOT \
  -jar /app/app.jar --spring.profiles.active=eval \
  > /tmp/eval-std-worker.log 2>&1 &
echo "worker pid=$!"
sleep 25
grep -E "Started ControlApplication|注册表装载|ERROR|REJECTED" /tmp/eval-std-worker.log | tail -6

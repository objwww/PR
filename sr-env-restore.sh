#!/bin/sh
# deploy/.env 重建：rsync --delete 误删恢复。真值源 = live 容器 env（上次 up 的插值结果），
# bak（2026-09-07 快照）兜底未运行服务的变量（如 holmes）。值不回显，只报变量名。
set -e
DEPLOY=/opt/build/pr/deploy
BAK=/opt/build/pr.bak-20260907T134128Z/deploy/.env
OUT=/opt/build/.env.rebuilt

VARS="POSTGRES_DB POSTGRES_PASSWORD PUBLISHER_DB_PASSWORD CONTROL_DB_PASSWORD \
ARENA_DB_PASSWORD CHAOS_ADMIN_DB_PASSWORD EVAL_DB_PASSWORD NOTIFY_DB_PASSWORD \
AGENT_MODEL AGENT_MODEL_API_KEY AGENT_MODEL_FALLBACK AGENT_MODEL_API_KEY_FALLBACK \
OPENAI_COMPAT_BASE_URL OPENAI_COMPAT_BASE_URL_FALLBACK \
CONTROL_WEBHOOK_BEARER_TOKEN ALERTMANAGER_WEBHOOK_BEARER_TOKEN \
APP_RELEASE_API_BEARER APP_OPERATOR_API_BEARER APP_DUTY_ADAPTER_BEARER \
AUTH_OPERATOR_USERNAME AUTH_OPERATOR_PASSWORD_BCRYPT \
APP_ALERT_EVAL_PROVIDER_FINGERPRINT APP_ALERT_NATIVE_METRICS_EXPR \
APP_ALERT_NATIVE_TOOL_REGISTRY_DIGEST APP_ALERT_HOLMES_MODEL \
APP_ALERT_R7_PRIMARY_ENABLED APP_ALERT_R7_PRIMARY_RELEASE_DIGEST \
APP_ALERT_R7_PRIMARY_PROMPT APP_ALERT_R7_PRIMARY_MAX_DELEGATION_BATCHES \
APP_ALERT_R7_INPUTCAPTURE APP_ALERT_AM4_PROMETHEUS_SERVICE_ALLOWLIST \
APP_ALERT_AM4_BUDGET_TOOL_CALLS APP_TRACE_SAMPLING \
OTEL_EXPORTER_OTLP_ENDPOINT OTEL_UPSTREAM_ENDPOINT OTEL_UPSTREAM_TOKEN \
OTEL_UPSTREAM_INSECURE \
NOTIFY_CHANNEL_TEST_WEBHOOK NOTIFY_CHANNEL_TEST_SECRET \
NOTIFY_CHANNEL_ONCALL_WEBHOOK NOTIFY_CHANNEL_ONCALL_SECRET \
HOLMES_API_KEY HOLMES_BASE_URL CHAOS_ADMIN_TOKEN ARENA_PORT TRAFFIC_ENABLED \
DUTY_K_DEAD_WEBHOOK CONTROL_BIND CONTROL_PORT WEB_BIND WEB_PORT"

# 容器优先级：control-app（业务面变量最多）→ web/notify/postgres/otelcol → arena/chaos → 其余
PRIORITY="deploy-control-app-1 deploy-web-1 deploy-notify-app-1 deploy-postgres-1 deploy-otelcol-control-1 alert-order-arena-1 alert-arena-chaos-admin-1"
REST="$(docker ps --format '{{.Names}}' | grep '^deploy-\|^alert-' | grep -vE '^(deploy-control-app-1|deploy-web-1|deploy-notify-app-1|deploy-postgres-1|deploy-otelcol-control-1|alert-order-arena-1|alert-arena-chaos-admin-1)$' | tr '\n' ' ')"
CONTAINERS="$PRIORITY $REST"

{
  echo "# 195 主栈运行时密钥（不入 git）"
  echo "# $(date -u +%Y-%m-%dT%H:%M:%SZ) 重建：live 容器 env 为真值源 + 2026-09-07 bak 兜底（rsync --delete 误删恢复）"
  : > /opt/build/.env.missing
  for v in $VARS; do
    line=""
    for c in $CONTAINERS; do
      cand="$(docker inspect "$c" --format '{{range .Config.Env}}{{println .}}{{end}}' 2>/dev/null | grep -m1 "^${v}=" || true)"
      if [ -n "$cand" ]; then line="$cand"; break; fi
    done
    if [ -z "$line" ]; then
      line="$(grep -m1 "^${v}=" "$BAK" 2>/dev/null || true)"
      [ -n "$line" ] && echo "bak-fallback: $v" >&2
    fi
    if [ -n "$line" ]; then
      printf '%s\n' "$line"
    else
      echo "$v" >> /opt/build/.env.missing
    fi
  done
} > "$OUT"

echo "=== 重建结果：$(grep -cE '^[A-Z]' $OUT) 个变量写入 $OUT ==="
echo "=== 未找到变量（若为 :? 必填则需人工补） ==="
cat /opt/build/.env.missing || echo "(无)"
echo "=== 与 bak 的差异变量名（期望仅模型块 am3 覆盖） ==="
diff <(grep -oE '^[A-Z_0-9]+' "$BAK" | sort) <(grep -oE '^[A-Z_0-9]+' "$OUT" | sort) || true
echo "=== 值差异变量名 ==="
for v in $(grep -oE '^[A-Z_0-9]+' "$BAK" | sort -u); do
  old="$(grep -m1 "^${v}=" "$BAK" | cut -d= -f2-)"
  new="$(grep -m1 "^${v}=" "$OUT" | cut -d= -f2-)"
  [ "$old" != "$new" ] && echo "CHANGED: $v"
done
exit 0

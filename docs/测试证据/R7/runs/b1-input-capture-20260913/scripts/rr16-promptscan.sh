#!/bin/sh
# RR16 模型输入半面：FULL 捕获 prompt 的秘密扫描（值不回显只打计数）
set -u
OUT="${1:-/tmp/b1fullcap}"
ENVF=/opt/build/pr/deploy/.env
vals=""
for k in APP_OPERATOR_API_BEARER APP_RELEASE_API_BEARER APP_DUTY_ADAPTER_BEARER \
         ALERTMANAGER_WEBHOOK_BEARER_TOKEN CONTROL_WEBHOOK_BEARER_TOKEN \
         AGENT_MODEL_API_KEY NOTIFY_CHANNEL_ONCALL_SECRET NOTIFY_CHANNEL_TEST_SECRET \
         AUTH_OPERATOR_PASSWORD_BCRYPT POSTGRES_PASSWORD; do
  v=$(grep -E "^$k=" "$ENVF" | head -1 | cut -d= -f2-)
  [ -n "$v" ] && [ ${#v} -ge 12 ] && vals="$vals $k:$v"
done
TOTAL=0
for f in "$OUT"/prompt-*.txt; do
  [ -f "$f" ] || continue
  for pair in $vals; do
    v="${pair#*:}"
    C=$(grep -c -F "$v" "$f" 2>/dev/null); C=${C:-0}
    [ "$C" -gt 0 ] && { echo "  FAIL: $f 含 ${pair%%:*}"; TOTAL=$((TOTAL+C)); }
  done
done
echo "prompt 秘密出现总数: $TOTAL（期望 0）"
[ "$TOTAL" -eq 0 ] && echo "RR16-PROMPTSCAN-PASS" || echo "RR16-PROMPTSCAN-FAIL"

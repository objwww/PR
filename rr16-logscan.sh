#!/bin/sh
# RR16（OR-04）只读半面：秘密值零落日志核查——对 bearer/密钥值在日志面计数（只打计数与位数）
set -u
ENVF=/opt/build/pr/deploy/.env
OUT=/tmp/rr16
rm -rf "$OUT"; mkdir -p "$OUT"

# 取秘密值（不回显）
vals=""
for k in APP_OPERATOR_API_BEARER APP_RELEASE_API_BEARER APP_DUTY_ADAPTER_BEARER \
         ALERTMANAGER_WEBHOOK_BEARER_TOKEN CONTROL_WEBHOOK_BEARER_TOKEN \
         AGENT_MODEL_API_KEY NOTIFY_CHANNEL_ONCALL_SECRET NOTIFY_CHANNEL_TEST_SECRET \
         AUTH_OPERATOR_PASSWORD_BCRYPT POSTGRES_PASSWORD; do
  v=$(grep -E "^$k=" "$ENVF" | head -1 | cut -d= -f2-)
  [ -n "$v" ] && vals="$vals $k:$v"
done
echo "受检秘密数: $(echo $vals | wc -w)（值不回显）"

LOGS="docker-logs-control"
docker logs deploy-control-app-1 > "$OUT/control.log" 2>&1
docker logs deploy-notify-app-1 > "$OUT/notify.log" 2>&1 || : > "$OUT/notify.log"
docker logs deploy-web-1 > "$OUT/web.log" 2>&1 || : > "$OUT/web.log"

TOTAL=0
for pair in $vals; do
  k="${pair%%:*}"; v="${pair#*:}"
  # 长度过短（<12）跳过防误报
  [ ${#v} -lt 12 ] && continue
  C1=$(grep -c -F "$v" "$OUT/control.log" 2>/dev/null); C1=${C1:-0}
  C2=$(grep -c -F "$v" "$OUT/notify.log" 2>/dev/null); C2=${C2:-0}
  C3=$(grep -c -F "$v" "$OUT/web.log" 2>/dev/null); C3=${C3:=0}; C3=${C3:-0}
  T=$((C1+C2+C3))
  TOTAL=$((TOTAL+T))
  echo "  $k (len=${#v}): control=$C1 notify=$C2 web=$C3"
done
echo "秘密值日志出现总数: $TOTAL（期望 0）"
[ "$TOTAL" -eq 0 ] && echo "RR16-LOGSCAN-PASS" || echo "RR16-LOGSCAN-FAIL"
echo "（RLM 捕获面/模型输入面的 RR16 半面：待 FULL 捕获 run 的 prompt 秘密扫描一并断言）"
echo "RR16-DONE"

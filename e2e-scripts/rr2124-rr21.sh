#!/bin/sh
# rr2124-rr21.sh —— RR21（OR-06）：隔离 PG 暂不可写时发送告警及模型动作
# 注入=iso PG ALTER SYSTEM default_transaction_read_only=on + reload + terminate 应用后端
# （Hikari 重连即新会话只读；非临时测试表真写拒验证；不暂停共享 DB——iso 专用实例）
# 断言=不假 ACK（503 非 202）/模型工具新发送计数为零/恢复后同载荷重投单 run/ACK 丢失重投不重复效果
set -u
. /opt/build/pr/rr-iso/rr-iso-openv.sh
. /opt/build/b2tree/e2e-r7-common.sh
LOGD=/opt/build/pr-logs/rr21
mkdir -p "$LOGD"
V="$LOGD/rr21-verdicts.txt"; : > "$V"
WH="http://127.0.0.1:18091/webhooks/alertmanager"
BEARER=$(grep -E '^ALERTMANAGER_WEBHOOK_BEARER_TOKEN=' /opt/build/pr/rr-iso/rr-iso.env | cut -d= -f2-)
Q() { docker exec rriso-postgres-1 psql -U postgres -d pr_agent -At -c "$1"; }
STS=$(date +%s)
AN="E2ERR21ReadOnly"
FP="e2err21ro-$STS"
WL_L="alertname=$(echo "$AN" | tr '[:upper:]' '[:lower:]')|service=checkout"
WLKEY="${WL_L}:${WL_L}"

cat > "$LOGD/alert.body" <<EOF
{"version":"4","receiver":"e2e","groupKey":"$AN","status":"firing","groupLabels":{},"commonLabels":{"alertname":"$AN","service":"checkout"},"commonAnnotations":{},"externalURL":"","truncatedAlerts":0,"alerts":[{"status":"firing","labels":{"alertname":"$AN","service":"checkout"},"annotations":{"summary":"rr21 read-only vehicle $STS"},"startsAt":"2026-09-13T09:30:00Z","generatorURL":"","fingerprint":"$FP"}]}
EOF

echo "== 0) 前置：iso 栈健康 + RR21 车 bundle 预激活（可写窗口内完成）=="
HC=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:18091/actuator/health)
echo "health=$HC"
SUITE="rr21-$STS"; RUNS="${R7_RUNS_DIR}/$SUITE"; mkdir -p "$RUNS"
cat > "$RUNS/bundle.content" <<EOF
{"policy_version":"rr21-${SUITE}","canary":{"percent":0,"whitelist":["alertname=${AN}|service=checkout"],"max_native_runs":500},"native":{"proposal":{"schema_version":"am4-plan.v1","tasks":[{"key":"investigate-metrics","type":"metrics@1","inputs":[]},{"key":"investigate-logs","type":"logs@1","inputs":[]},{"key":"investigate-change","type":"change@1","inputs":[]}],"edges":[]}}}
EOF
DA="$(r7_publish_bundle "$RUNS/bundle.content" rr21)" && r7_qualify "$DA" "$RUNS" >/dev/null && r7_activate "$DA" "$RUNS" >/dev/null && echo "bundle 激活 ok digest=${DA%????????????????????????????????????????????????????????????}…" || { echo "RR21 ABORT：bundle 未激活"; exit 2; }

echo "== 1) 基线计数 =="
B_INBOX=$(Q "select count(*) from alert_inbox")
B_RUN=$(Q "select count(*) from rca_run")
B_MC=$(Q "select count(*) from rca_model_call")
B_TI=$(Q "select count(*) from rca_tool_invocation")
echo "baseline inbox=$B_INBOX run=$B_RUN model_call=$B_MC tool_inv=$B_TI"

echo "== 2) 注入只读（ALTER SYSTEM+reload+terminate 应用后端）=="
Q "show default_transaction_read_only" > "$LOGD/guc-before.txt"
Q "alter system set default_transaction_read_only = on" >/dev/null && Q "select pg_reload_conf()" >/dev/null
Q "select pg_terminate_backend(pid) from pg_stat_activity where datname='pr_agent' and pid <> pg_backend_pid()" > "$LOGD/terminated.txt"
sleep 3
echo "-- 真表写拒验证（alert_inbox，非临时表）--"
Q "insert into alert_inbox (id) values (gen_random_uuid())" > "$LOGD/write-reject.txt" 2>&1
if grep -q 'read-only transaction' "$LOGD/write-reject.txt"; then
  echo "真表写拒 PASS：$(cat "$LOGD/write-reject.txt" | head -1)" | tee -a "$V"
else
  echo "真表写拒 FAIL：$(cat "$LOGD/write-reject.txt" | head -1)" | tee -a "$V"
fi
HC2=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:18091/actuator/health)
echo "只读期 health=$HC2（读路径不受影响）" | tee -a "$LOGD/readonly-health.txt"

echo "== 3) 只读期发唯一告警 → 必须 503 不假 ACK =="
TS3=$(date -u +%Y-%m-%dT%H:%M:%S)
CODE=$(curl -s -o "$LOGD/resp-readonly.body" -w '%{http_code}' -X POST "$WH" -H "Authorization: Bearer $BEARER" -H 'Content-Type: application/json' --data @"$LOGD/alert.body")
echo "只读期 POST code=$CODE body=$(head -c 120 "$LOGD/resp-readonly.body")"
sleep 5
docker logs rriso-control-app-1 --since "$TS3" > "$LOGD/app-readonly.log" 2>&1
LOGHIT=$(grep -c '无法持久化' "$LOGD/app-readonly.log" || true)
echo "app 日志『无法持久化』命中=$LOGHIT"
A_INBOX=$(Q "select count(*) from alert_inbox"); A_RUN=$(Q "select count(*) from rca_run")
A_MC=$(Q "select count(*) from rca_model_call"); A_TI=$(Q "select count(*) from rca_tool_invocation")
echo "只读期增量 inbox=$((A_INBOX-B_INBOX)) run=$((A_RUN-B_RUN)) model_call=$((A_MC-B_MC)) tool_inv=$((A_TI-B_TI))"
if [ "$CODE" = "503" ] && [ "$A_INBOX" = "$B_INBOX" ] && [ "$A_RUN" = "$B_RUN" ] && [ "$A_MC" = "$B_MC" ] && [ "$A_TI" = "$B_TI" ]; then
  echo "不假ACK+零新发送 PASS（503 非假 202；模型/工具/账本四表零增量）" | tee -a "$V"
else
  echo "不假ACK+零新发送 FAIL（code=$CODE 增量见上）" | tee -a "$V"
fi
[ "${LOGHIT:-0}" -ge 1 ] && echo "接收器日志留痕 PASS（无法持久化 WARN 行在）" | tee -a "$V" || echo "接收器日志留痕 FAIL" | tee -a "$V"

echo "== 4) 恢复（off+reload+terminate）+ 同载荷重投 → 单 run =="
Q "alter system set default_transaction_read_only = off" >/dev/null && Q "select pg_reload_conf()" >/dev/null
Q "select pg_terminate_backend(pid) from pg_stat_activity where datname='pr_agent' and pid <> pg_backend_pid()" >/dev/null
sleep 3
Q "show default_transaction_read_only" > "$LOGD/guc-after.txt"
CODE2=$(curl -s -o "$LOGD/resp-recovered.body" -w '%{http_code}' -X POST "$WH" -H "Authorization: Bearer $BEARER" -H 'Content-Type: application/json' --data @"$LOGD/alert.body")
echo "恢复期 POST code=$CODE2 body=$(head -c 120 "$LOGD/resp-recovered.body")"
RID=""
i=0; while [ $i -lt 24 ]; do
  RID=$(Q "select run_id from canary_route_decision where stickiness_key='${WLKEY}' and decision='WHITELISTED' and bundle_digest='${DA}' and run_id is not null order by id desc limit 1")
  [ -n "$RID" ] && break; sleep 5; i=$((i+1))
done
STATE="absent"
if [ -n "$RID" ]; then STATE=$(r7_wait_run_terminal R7_PG_URL "$RID" 600); fi
echo "run=$RID state=$STATE"
N_RUN=$(Q "select count(distinct run_id) from canary_route_decision where stickiness_key='${WLKEY}' and decision='WHITELISTED' and run_id is not null")
if [ "$CODE2" = "202" ] && [ -n "$RID" ] && [ "$N_RUN" = "1" ]; then
  echo "恢复重投 PASS（202→WHITELISTED 路由→run=$STATE；该告警 run 恰 1）" | tee -a "$V"
else
  echo "恢复重投 FAIL（code=$CODE2 run=$RID state=$STATE n_run=$N_RUN）" | tee -a "$V"
fi

echo "== 5) ACK 丢失面：同载荷再投（AM 视角重试）→ 效果不重复 =="
CODE3=$(curl -s -o "$LOGD/resp-ackloss.body" -w '%{http_code}' -X POST "$WH" -H "Authorization: Bearer $BEARER" -H 'Content-Type: application/json' --data @"$LOGD/alert.body")
echo "ACK 丢失重投 code=$CODE3"
sleep 40
N_RUN2=$(Q "select count(distinct run_id) from canary_route_decision where stickiness_key='${WLKEY}' and decision='WHITELISTED' and run_id is not null")
N_INBOX2=$(Q "select count(*) from alert_inbox")
echo "重投后 n_run=$N_RUN2 inbox_total=$N_INBOX2（基线 $B_INBOX）"
if [ "$N_RUN2" = "1" ]; then
  echo "幂等不重复 PASS（重复投递 run 恒 1——效果幂等）" | tee -a "$V"
else
  echo "幂等不重复 FAIL（n_run=$N_RUN2）" | tee -a "$V"
fi

echo "== 6) 在栈不变量 =="
curl -s -o /dev/null -w 'standing-health=%{http_code}\n' http://127.0.0.1:8080/actuator/health | tee -a "$V"
docker inspect deploy-control-app-1 --format 'standing-restarts={{.RestartCount}}' | tee -a "$V"
echo "== RR21 完成（GUC 已复原 off；证据 $LOGD）=="
cat "$V"

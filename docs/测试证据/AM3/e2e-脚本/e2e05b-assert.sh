#!/bin/sh
# E2E-M3-05 崩溃重驱与冻结语义 · 断言 v3（按现实校准；2026-09-06 悬案定性后）
#
# 现实面（悬案定性结论：冻结语义的正确执行，非缺陷）：
#   KILL5 杀 notify-app 于 20s 延迟投递第 1s → 租约(60s)过期被 claim 折叠回收重领
#   （lease_epoch 1→2，claim 不计 attempt）→ 重发撞上仍在位的 20s stub →
#   JdkWebhookTransport 10s 请求超时 → M3-23 冻结：结果未知→DEAD 不自动重发，
#   诚实话术 last_error=outcome_unknown；publication 聚合 DEAD；批件不受阻断 SUCCEEDED。
#   attempt_count=0 为 DEAD 路径不耗预算计数的冻结语义；两次线上投递由
#   journal 增长（22→25）+ lease_epoch=2 共同证明。
set -u
TAG=am3-e2e05-0905
DRILLLOG=/tmp/m330-e2e05b-drill.log
q() { docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tAc "$1" 2>/dev/null | tr -d ' \n'; }
ck() {
  if [ "$2" = "$3" ]; then echo "E2E|PASS|$1|$4"; else echo "E2E|FAIL|$1|期望=$2 实际=$3 $4"; fails=$((fails+1)); fi
}
ckz() {
  if [ "$3" -ge "$2" ] 2>/dev/null; then echo "E2E|PASS|$1|$4"; else echo "E2E|FAIL|$1|期望≥$2 实际=$3 $4"; fails=$((fails+1)); fi
}
fails=0
RUN=$(q "select id from eval_run where dataset_version='$TAG' order by started_at desc limit 1")
ST=$(q "select state from eval_run where id='$RUN'")
echo "== 断言（run=$RUN） =="

ck "h00:批件锚定且SUCCEEDED" "SUCCEEDED" "$ST" "run=$RUN"

ck "h01:杀点已执行" "1" "$(grep -c 'KILL5=FIRED' $DRILLLOG)" "$(grep -o 'KILL5=FIRED at [^ ]*' $DRILLLOG | head -1)"

B=$(grep -o 'baseline=[0-9]*' $DRILLLOG | head -1 | cut -d= -f2)
K=$(grep -o 'journal=[0-9]*' $DRILLLOG | head -1 | cut -d= -f2)
if [ "${K:-0}" -gt "${B:-0}" ] 2>/dev/null; then
  echo "E2E|PASS|h02:首投已出网（journal 增长）|baseline=$B kill时=$K"
else
  echo "E2E|FAIL|h02:首投已出网（journal 增长）|baseline=$B kill时=$K"; fails=$((fails+1))
fi

v=$(q "select verdict from eval_case_result where eval_run_id='$RUN' and round_no=1")
ck "h03:r1诚实缺席" "TIMEOUT_OR_ABSENT" "$v" "r1=$v"
v=$(q "select verdict from eval_case_result where eval_run_id='$RUN' and round_no=2")
ck "h04:r2正常出判定" "DECIDABLE" "$v" "r2=$v"

OB=$(q "select id from notify_outbox where updated_at > (select started_at from eval_run where id='$RUN') order by updated_at limit 1")
v=$(q "select state from notify_outbox where id='$OB'")
ck "h05:outbox冻结DEAD（结果未知不重发）" "DEAD" "$v" "outbox=$OB"
LE=$(q "select coalesce(last_error::text,'-') from notify_outbox where id='$OB'")
case "$LE" in *outcome_unknown*) echo "E2E|PASS|h06:诚实话术outcome_unknown|$LE";; *) echo "E2E|FAIL|h06:诚实话术outcome_unknown|$LE"; fails=$((fails+1));; esac
v=$(q "select lease_epoch from notify_outbox where id='$OB'")
ck "h07:崩溃后租约过期重领（epoch=2）" "2" "$v" "lease_epoch=$v"
v=$(q "select attempt_count from notify_outbox where id='$OB'")
ck "h08:DEAD路径不耗attempt预算（冻结语义）" "0" "$v" "attempt_count=$v（两次线上投递由h02+h07证明）"
v=$(q "select p.state from report_publication p where p.id=(select publication_id from notify_outbox where id='$OB')")
ck "h09:publication聚合DEAD" "DEAD" "$v" "pub=$v"

v=$(q "select string_agg(status,'/') from (select status from alert_event where recorded_at > (select started_at from eval_run where id='$RUN') order by recorded_at) t")
ck "h10:告警代际四拍循环" "firing/resolved/firing/resolved" "$v" "beats=$v"
v=$(q "select status||'@'||generation from incident where incident_key like '%ArenaDuplicateOrders%' and updated_at > (select started_at from eval_run where id='$RUN') order by updated_at desc limit 1")
ck "h11:incident终态RESOLVED@13" "RESOLVED@13" "$v" "incident=$v"
v=$(q "select count(*) from arena.oa_chaos_session where scenario_id like 'chaos-eval-$TAG-%' and state='CLOSED'")
ck "h12:两会话均CLOSED" "2" "$v" "closed=$v"

echo "E2E|SUBTOTAL|e2e05b|fail=$fails"
exit $fails

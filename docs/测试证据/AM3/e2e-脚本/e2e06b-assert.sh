#!/bin/sh
# E2E-M3-06 batch-b（am3-e2e06b-0906）断言 + 无条件恢复
# 设计面（§12 E2E-M3-06）：proxy 停机 → run 存在但调查失败（FAILED）→ 无validated报告 →
#   无报告/无通知/无成功调查；verdict TIMEOUT_OR_ABSENT（不得错误 MATCHED）；
#   usage 对账降级 UNMATCHED 落台账；批件终态不受对账失败阻断。
# 相对批1（am3-e2e06-0905，两轮 run_not_found 诚实缺席）的增强面：两轮均拿到 run 且 FAILED。
set -u
TAG=am3-e2e06b-0906
LOG=/tmp/m330-am3-e2e06b-0906-run.log
q() { docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tAc "$1" 2>/dev/null | tr -d ' \n'; }
ck() {
  if [ "$2" = "$3" ]; then echo "E2E|PASS|$1|$4"; else echo "E2E|FAIL|$1|期望=$2 实际=$3 $4"; fails=$((fails+1)); fi
}
fails=0
RUN=$(q "select id from eval_run where dataset_version='$TAG' order by started_at desc limit 1")
R1=$(q "select coalesce(rca_run_id::text,'') from eval_case_result where eval_run_id='$RUN' and scenario_id='S3' and round_no=1 limit 1")
R2=$(q "select coalesce(rca_run_id::text,'') from eval_case_result where eval_run_id='$RUN' and scenario_id='S3' and round_no=2 limit 1")

echo "== 断言（run=$RUN r1=$R1 r2=$R2） =="
ck "e00:批件锚定" "36" "${#RUN}" "run=$RUN"
v=$(q "select state from eval_run where id='$RUN'")
ck "e01:批件终态SUCCEEDED（对账失败不阻断）" "SUCCEEDED" "$v" "state=$v"
v=$(q "select count(*) from eval_case_result where eval_run_id='$RUN' and scenario_id='S3' and verdict='TIMEOUT_OR_ABSENT'")
ck "e02:两轮TIMEOUT_OR_ABSENT（不得错误MATCHED）" "2" "$v" "absent_cases=$v"
v=$(q "select count(*) from eval_case_result where eval_run_id='$RUN' and failure_sample::text like '%no_validated_report%'")
ck "e02b:缺席原因=no_validated_report（run存在但无validated报告）" "2" "$v" "samples=$v"
v=$(q "select count(*) from eval_case_result where eval_run_id='$RUN' and rca_run_id is not null")
ck "e02c:两轮均锚定调查run（增强面，批1为空）" "2" "$v" "anchored=$v"
v=$(q "select count(*) from rca_run where id in ('$R1','$R2') and state='FAILED'")
ck "e02d:两个调查run均FAILED终态" "2" "$v" "failed_runs=$v"
v=$(q "select count(*) from rca_run where id in ('$R1','$R2') and generation in (9,10)")
ck "e02e:代际推进9/10（resolve/firing 全投递）" "2" "$v" "gens=$v"
v=$(q "select count(*) from rca_report where run_id in ('$R1','$R2')")
ck "e03:零报告" "0" "$v" "reports=$v"
v=$(q "select count(*) from notify_outbox o join rca_report rr on o.report_id=rr.id where rr.run_id in ('$R1','$R2')")
ck "e04:零通知" "0" "$v" "outbox=$v"
v=$(q "select count(*) from rca_investigation_result where run_id in ('$R1','$R2') and execution_status='SUCCEEDED'")
ck "e05:零成功调查" "0" "$v" "succeeded=$v"
v=$(q "select count(*) from alert_event where recorded_at > '2026-09-06 05:37+00' and generation in (9,10)")
ck "e06:事件全循环4行（firing9/resolved9/firing10/resolved10）" "4" "$v" "events=$v"

echo "-- usage ledger（对账降级面） --"
if grep -q '"state":"UNMATCHED"' "$LOG"; then
  echo "E2E|PASS|e07:对账降级UNMATCHED|ledger_state=UNMATCHED"
else
  echo "E2E|FAIL|e07:对账降级UNMATCHED|no UNMATCHED state in log"; fails=$((fails+1))
fi
n=$(grep -c '"state":"MATCHED"' "$LOG")
ck "e07b:无伪造MATCHED" "0" "$n" "matched_lines=$n"
n=$(grep -o '"attempt_id"' "$LOG" | wc -l | tr -d ' ')
ck "e07c:6个attempt全落台账（expected=6）" "6" "$n" "attempts=$n"

v=$(docker inspect deploy-control-app-1 --format '{{.RestartCount}}' 2>/dev/null || echo 999)
ck "e08:control-app零崩溃循环" "0" "$v" "restarts=$v"
v=$(q "select state from arena.oa_chaos_session where scenario_id like 'chaos-eval-$TAG-%' order by created_at desc limit 1")
ck "e09:靶场会话CLOSED" "CLOSED" "$v" "state=$v"

echo "== 恢复（无条件）：litellm 拉起 + AM 配置还原 =="
docker start litellm-am3
i=0
until [ "$(docker inspect litellm-am3 --format '{{.State.Health.Status}}' 2>/dev/null)" = "healthy" ]; do
  i=$((i+1)); [ "$i" -gt 40 ] && break
  sleep 5
done
docker ps --filter name=litellm-am3 --format '{{.Names}} {{.Status}}'
sed -i 's/^  group_interval: 10s$/  group_interval: 5m/' /opt/build/pr/deploy/alert/alertmanager/alertmanager.yml
grep -nE 'group_interval' /opt/build/pr/deploy/alert/alertmanager/alertmanager.yml
docker restart alertmanager-am0 >/dev/null && echo "AM restarted (config restored)"
sleep 5
curl -s http://127.0.0.1:9093/api/v2/status | python3 -c "
import json,sys
c=json.load(sys.stdin)['config']['original']
for ln in c.splitlines():
    if 'group_interval' in ln: print('runtime:',ln.strip())
"

echo "E2E|SUBTOTAL|e2e06b|fail=$fails"
exit $fails

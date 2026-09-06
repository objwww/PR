#!/bin/sh
# E2E-M3-06 LiteLLM 故障链 · 断言 + 无条件恢复（docker start litellm-am3）
# 前置：批 am3-e2e06-0905 已退出。
set -u
TAG=am3-e2e06-0905
q() { docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tAc "$1" 2>/dev/null | tr -d ' \n'; }
ck() {
  if [ "$2" = "$3" ]; then echo "E2E|PASS|$1|$4"; else echo "E2E|FAIL|$1|期望=$2 实际=$3 $4"; fails=$((fails+1)); fi
}
ckz() {
  if [ "$3" -ge "$2" ] 2>/dev/null; then echo "E2E|PASS|$1|$4"; else echo "E2E|FAIL|$1|期望≥$2 实际=$3 $4"; fails=$((fails+1)); fi
}
fails=0
RUN=$(q "select id from eval_run where dataset_version='$TAG' order by started_at desc limit 1")
RCARUN=$(q "select coalesce(rca_run_id::text,'') from eval_case_result where eval_run_id='$RUN' and scenario_id='S3' and round_no=1 limit 1")

echo "== 断言（run=$RUN rca_run=$RCARUN） =="
ck "e00:批件锚定" "36" "${#RUN}" "run=$RUN"
v=$(q "select state from eval_run where id='$RUN'")
ck "e01:批件终态SUCCEEDED（对账失败不阻断）" "SUCCEEDED" "$v" "state=$v"
v=$(q "select count(*) from eval_case_result where eval_run_id='$RUN' and scenario_id='S3' and verdict='TIMEOUT_OR_ABSENT'")
ck "e02:两轮TIMEOUT_OR_ABSENT（不得错误MATCHED）" "2" "$v" "absent_cases=$v"
v=$(q "select count(*) from rca_report where run_id='$RCARUN'")
ck "e03:零报告" "0" "$v" "reports=$v"
v=$(q "select count(*) from notify_outbox o join rca_report rr on o.report_id=rr.id where rr.run_id='$RCARUN'")
ck "e04:零通知" "0" "$v" "outbox=$v"
v=$(q "select count(*) from rca_investigation_result where run_id='$RCARUN' and execution_status='SUCCEEDED'")
ck "e05:零成功调查" "0" "$v" "succeeded=$v"

echo "-- usage ledger（对账降级面） --"
if grep -q '"state":"UNMATCHED"' /tmp/m330-am3-e2e06-0905-run.log; then
  echo "E2E|PASS|e06:对账降级UNMATCHED|ledger_state=UNMATCHED"
else
  echo "E2E|FAIL|e06:对账降级UNMATCHED|no UNMATCHED state in log"; fails=$((fails+1))
fi
n=$(grep -c '"state":"MATCHED"' /tmp/m330-am3-e2e06-0905-run.log || echo 0)
ck "e06b:无伪造MATCHED" "0" "$n" "matched_lines=$n"

v=$(docker inspect deploy-control-app-1 --format '{{.RestartCount}}' 2>/dev/null || echo 999)
ck "e07:control-app零崩溃循环" "0" "$v" "restarts=$v"
v=$(q "select state from arena.oa_chaos_session where scenario_id like 'chaos-eval-$TAG-%' order by created_at desc limit 1")
ck "e08:靶场会话CLOSED" "CLOSED" "$v" "state=$v"

echo "== 恢复 litellm（无条件） =="
docker start litellm-am3
i=0
until [ "$(docker inspect litellm-am3 --format '{{.State.Health.Status}}' 2>/dev/null)" = "healthy" ]; do
  i=$((i+1)); [ "$i" -gt 40 ] && break
  sleep 5
done
docker ps --filter name=litellm-am3 --format '{{.Names}} {{.Status}}'

echo "E2E|SUBTOTAL|e2e06|fail=$fails"
exit $fails

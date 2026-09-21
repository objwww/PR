#!/bin/sh
# b2-cl06-v2-probe6.sh —— attempt3 异常取证：run SUCCEEDED 但 PRIMARY 非 DONE
. /opt/build/r7-operator-env.sh
. /opt/build/b2tree/e2e-r7-common.sh
G() { r7_psql_ro R7_PG_URL "$1" '-At'; }
r=3564fee0-236f-4fb8-87c7-b17a197f86b4
echo "== run 行："
G "select state, finished_at is not null, started_at::text, finished_at::text from rca_run where id='$r'"
echo "== tasks 终态："
G "select task_key||' :: '||state||' :: attempts='||attempt_count from rca_task where run_id='$r' order by created_at"
echo "== checkpoint："
G "select round_id||'|'||steps_used||'|'||decision_seq||'|'||batches_used||'|'||(final_claims is not null) from rca_primary_checkpoint where run_id='$r'"
echo "== 报告："
G "select count(*) from rca_report where run_id='$r'"
echo "== t3 log 末 12 行："
tail -12 /opt/build/pr-logs/b2-cl06-v2-t3.log

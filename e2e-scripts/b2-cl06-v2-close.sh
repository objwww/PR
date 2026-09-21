#!/bin/sh
# b2-cl06-v2-close.sh —— b3 快照 + BA-141 报告取证 + 复原前置态检查
sh /opt/build/b2-cl06-v2-snap.sh v5.2-b3 >/dev/null 2>&1
echo "== b3 aggregate =="
cat /opt/build/pr-logs/b2cl06-v2/v5.2-b3/b2-cl06-v2-aggregate.log
. /opt/build/r7-operator-env.sh
. /opt/build/b2tree/e2e-r7-common.sh
G() { r7_psql_ro R7_PG_URL "$1" '-At'; }
r=3564fee0-236f-4fb8-87c7-b17a197f86b4
echo "== BA-141 取证：异常 run 的报告（诚实性面） =="
G "select left(coalesce(summary,'(null)'),200) from rca_report where run_id='$r'"
echo "== checkpoint final_claims/missing："
G "select left(final_claims::text,300) from rca_primary_checkpoint where run_id='$r'"
G "select left(missing_information::text,300) from rca_primary_checkpoint where run_id='$r'"
echo "== 复原前置：当前 override/env 态 =="
grep -c '两批委派\|INPUTCAPTURE' /opt/build/b2-cl06-override.yml
grep -n 'INPUTCAPTURE\|MAX_DELEGATION' /opt/build/pr/deploy/.env 2>/dev/null || echo "env 无残留键"

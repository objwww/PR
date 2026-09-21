#!/bin/sh
# b2-cl06-v2-probe3.sh —— v5.1 phase9 记忆行=1 取证（driver 同款 psql 通道）
. /opt/build/r7-operator-env.sh
. /opt/build/b2tree/e2e-r7-common.sh
G() { r7_psql_ro R7_PG_URL "$1" '-At'; }
for r in bbf9af6a-8b1e-4fa3-80bc-d8672722c003 7fdbce6c-9f2c-438e-a5ff-a19485ca1a67; do
  echo "=== run=$r"
  echo "-- memory rows:"
  G "select checkpoint_revision||' parent='||coalesce(parent_memory_id::text,'-')||' '||left(memory_digest,12) from rca_working_memory where run_id='$r' order by checkpoint_revision"
  echo "-- tool invocations:"
  G "select tool_name from rca_tool_invocation where run_id='$r' order by created_at"
  echo "-- tasks:"
  G "select task_key||' :: '||state from rca_task where run_id='$r' order by created_at"
  echo "-- checkpoint:"
  G "select round_id||'|'||steps_used||'|'||decision_seq||'|'||batches_used from rca_primary_checkpoint where run_id='$r'"
  echo "-- receipts:"
  G "select gap_id||' status='||status||' cref/miss='||coalesce(counter_refs::text,'-')||'/'||coalesce(missing_information::text,'-') from rca_delegation_receipt where run_id='$r'"
done

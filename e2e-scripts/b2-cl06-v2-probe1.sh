#!/bin/sh
# b2-cl06-v2-probe1.sh —— v2 首轮 FAIL 取证（phase5 change.query 面）
echo "=== t1 log phases ==="
grep -E '\[R7\] (phase|FAIL)' /opt/build/pr-logs/b2-cl06-v2-t1.log | tail -15
echo "=== aggregate ==="
cat /opt/build/pr-logs/b2-cl06-v2-aggregate.log
echo "=== per-run tasks & tools ==="
for d in /opt/build/runs-b2cl06v2/*/; do
  [ -f "$d/run-id.txt" ] || continue
  rid=$(cut -d= -f2 "$d/run-id.txt")
  echo "--- run=$rid"
  docker exec deploy-postgres-1 psql -U pr -d pr -At -c "select task_key||' :: '||state from rca_task where run_id='$rid' order by created_at"
  docker exec deploy-postgres-1 psql -U pr -d pr -At -c "select tool_name||' x'||count(*) from rca_tool_invocation where run_id='$rid' group by tool_name"
  docker exec deploy-postgres-1 psql -U pr -d pr -At -c "select count(*)||' memrows, rounds: '||string_agg(distinct checkpoint_revision::text,',') from rca_working_memory where run_id='$rid'"
done

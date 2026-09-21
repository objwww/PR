#!/bin/sh
. /opt/build/r7-operator-env.sh
echo '=== 等 18:27 run 终态（最长 8 分钟） ==='
i=0
while [ $i -lt 48 ]; do
  ROW=$($R7_PSQL_CMD "$R7_PG_URL" -tA -c "select r.state from rca_run r where r.created_at > now() - interval '20 minutes' and r.state in ('QUEUED','RUNNING','REPORTING') limit 1;")
  if [ -z "$ROW" ]; then
    echo "no-active-run"
    break
  fi
  sleep 10
  i=$((i+1))
done
echo "wait-done($i)"
$R7_PSQL_CMD "$R7_PG_URL" -tA -c "select r.created_at::time(0), r.state, t.task_key, t.state from rca_run r join rca_task t on t.run_id=r.id where r.created_at > now() - interval '25 minutes' order by r.created_at desc limit 3;"
echo '=== rerun A0 ==='
cd /opt/build/pr/docs/测试证据/R7/e2e-脚本
nohup sh -c '. /opt/build/r7-operator-env.sh && sh e2e-r7-a0-provider-receipt-chain.sh' > /opt/build/pr-logs/a0-path1-run10.log 2>&1 &
echo rerun-launched
exit 0

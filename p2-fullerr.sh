#!/bin/sh
echo '--- 第一处 ERROR 前后 40 行 ---'
grep -n 'ERROR' /tmp/p2-worker.log | head -5
N=$(grep -n 'ERROR' /tmp/p2-worker.log | head -1 | cut -d: -f1)
sed -n "$((N-6)),$((N+14))p" /tmp/p2-worker.log
echo '--- run 与命令终态 ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select state||' | '||coalesce(terminal_reason,'-') from eval_run where id='eed0e10d-e020-4f3c-abbc-161cbc166d1a';"
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select command_type||' | '||state from eval_run_command where eval_run_id='eed0e10d-e020-4f3c-abbc-161cbc166d1a';"
echo '--- 阶段事件（全部）---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select phase||' @ '||entered_at from eval_phase_event where eval_run_id='eed0e10d-e020-4f3c-abbc-161cbc166d1a' order by entered_at;"
echo '--- 案例行 ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select scenario_id||' r'||round_no||' | '||verdict from eval_case_result where eval_run_id='eed0e10d-e020-4f3c-abbc-161cbc166d1a';"

#!/bin/sh
echo '--- eval_phase_event ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select phase||' @ '||entered_at||' || '||coalesce(detail::text,'-') from eval_phase_event where eval_run_id='eed0e10d-e020-4f3c-abbc-161cbc166d1a' order by entered_at;"
echo '--- LAUNCH 命令状态 ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select command_type||' | '||state||' | claimed='||coalesce(claimed_at::text,'-')||' | epoch='||lease_epoch from eval_run_command where eval_run_id='eed0e10d-e020-4f3c-abbc-161cbc166d1a';"
echo '--- worker 线程栈里的关键词（jstack 兜底）---'
WPID=$(docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select 1" >/dev/null 2>&1; docker ps --format '{{.Names}}' | grep eval-worker)
docker exec $WPID sh -c "ls /proc/1/task 2>/dev/null | head -1" >/dev/null 2>&1 && echo "container=$WPID alive" || echo "container gone"
echo '--- 收件箱计数对比（总行数）---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select count(*) from alert_inbox;"

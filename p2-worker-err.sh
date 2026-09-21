#!/bin/sh
grep -B 3 -A 18 'ERROR\|Exception' /tmp/p2-worker.log | tail -60
echo '--- worker 进程是否还活着 ---'
docker ps --format '{{.Names}} | {{.Status}}' | grep eval
echo '--- 命令表当前 ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select left(eval_run_id::text,8)||' | '||command_type||' | '||state||' | claimed='||coalesce(claimed_at::text,'-')||' | attempts='||attempt_count from eval_run_command order by claimed_at desc nulls last limit 4;"
